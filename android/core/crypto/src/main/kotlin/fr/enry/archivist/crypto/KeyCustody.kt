package fr.enry.archivist.crypto

import java.security.PrivateKey
import java.security.PublicKey
import org.bouncycastle.crypto.InvalidCipherTextException

/**
 * Orchestrates plan step 2.5's enrolment and recovery flows on top of the primitives
 * elsewhere in this module ([EcdhEs], [AesKw], [Argon2Kdf], [RecoveryCode]). Deliberately
 * knows nothing about `AndroidKeyStore` — every function here takes a [PublicKey] /
 * [PrivateKey] rather than reaching into [DeviceKeystore] itself, so all of it (except
 * the parts that are inherently about *which* code the user typed) is a plain JVM unit
 * test, unlike [DeviceKeystore]. The `:app` module's repository layer is what wires a
 * real Keystore key into these functions.
 *
 * Never leaks a BouncyCastle exception type across the module boundary: a wrong KEK or
 * corrupted wrapping (`InvalidCipherTextException`) is caught here and turned into a
 * sealed result `:app` can switch on without depending on BC directly.
 */
object KeyCustody {
    /** A fresh 32-byte master key plus every wrapping needed to enrol the first
     * device: its own Keystore wrapping, a recovery wrapping, and the owner's wrapped
     * `hashSecret`. Nothing here has touched the network yet — see
     * `EnrolmentRepository` for why enrolment only POSTs after the user confirms the
     * recovery code. */
    data class FirstEnrolment(
        val masterKey: MasterKey,
        val deviceWrap: EcdhEs.Wrapped,
        val recoveryCode: RecoveryCode.Generated,
        val recoveryWrap: RecoveryWrap,
        val hashSecret: ByteArray,
        val encHashSecret: ByteArray,
    )

    data class RecoveryWrap(
        val kdfSalt: ByteArray,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
        val wrappedKey: ByteArray,
    )

    sealed interface DeviceUnwrapResult {
        data class Success(val masterKey: MasterKey) : DeviceUnwrapResult

        /** The wrapping itself is corrupted or was wrapped against a different key —
         * never the Keystore key having been invalidated; that's
         * `KeyPermanentlyInvalidatedException`, which is allowed to propagate out of
         * [unwrapForDevice] uncaught since the caller (already on Android) needs to
         * catch that platform exception anyway to trigger re-enrolment. */
        data object InvalidWrap : DeviceUnwrapResult
    }

    sealed interface RecoveryUnwrapResult {
        data class Success(val masterKey: MasterKey) : RecoveryUnwrapResult

        /** Failed the check symbol — a typo, caught before Argon2id ever runs. See
         * "Verification" in crypto-format.md. */
        data object Mistyped : RecoveryUnwrapResult

        /** Passed the check symbol but didn't unwrap — the wrong (but validly
         * checksummed) code, or a corrupted wrapping. A different message from
         * [Mistyped] on purpose; see crypto-format.md's "What the check symbol buys". */
        data object WrongCodeOrCorrupted : RecoveryUnwrapResult
    }

    /** Step 1 of first-device enrolment. [devicePublicKey] is this device's freshly
     * generated Keystore public key ([DeviceKeystore.ensureKeyPair]). */
    fun enrolFirstDevice(devicePublicKey: PublicKey): FirstEnrolment {
        val masterKey = MasterKey.of(EnvelopeCrypto.generateDek()) // same shape: 32 random bytes
        val deviceWrap = masterKey.wrapForDevice(devicePublicKey)
        val recoveryCode = RecoveryCode.generate()
        val recoveryWrap = masterKey.wrapForRecovery(recoveryCode.entropy)
        val hashSecret = EnvelopeCrypto.generateDek()
        val encHashSecret = masterKey.wrapHashSecret(hashSecret)
        return FirstEnrolment(masterKey, deviceWrap, recoveryCode, recoveryWrap, hashSecret, encHashSecret)
    }

    /** Cheap, instant, no Argon2id: does [typed] round-trip to the exact entropy
     * [enrolFirstDevice] generated? This is what the confirmation screen uses — no
     * need to exercise the KDF/AES-KW path at all, since equal entropy guarantees the
     * wrap already POSTed (if it has been) unwraps the same way. */
    fun confirmsGeneratedCode(
        typed: String,
        generated: RecoveryCode.Generated,
    ): Boolean = RecoveryCode.verify(typed) == generated.entropy

    /** The later-device / re-enrolment path: verify [rawCode], derive the KEK from the
     * server's recorded `kdfSalt`/`kdfParams`, and unwrap. */
    fun unwrapWithRecoveryCode(
        rawCode: String,
        kdfSalt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        wrappedKey: ByteArray,
    ): RecoveryUnwrapResult {
        val entropy = RecoveryCode.verify(rawCode) ?: return RecoveryUnwrapResult.Mistyped
        val kek = Argon2Kdf.kek(entropy.toByteArray(Charsets.US_ASCII), kdfSalt, memoryKib, iterations, parallelism)
        return try {
            RecoveryUnwrapResult.Success(MasterKey.of(AesKw.unwrap(kek, wrappedKey)))
        } catch (e: InvalidCipherTextException) {
            RecoveryUnwrapResult.WrongCodeOrCorrupted
        }
    }

    /** Silent unlock at app start: unwrap this device's own `W#` wrapping with its
     * Keystore-resident private key. Let `KeyPermanentlyInvalidatedException` escape
     * uncaught (see [DeviceUnwrapResult.InvalidWrap]'s doc). */
    fun unwrapForDevice(
        devicePrivateKey: PrivateKey,
        epk: ByteArray,
        wrappedKey: ByteArray,
    ): DeviceUnwrapResult =
        try {
            DeviceUnwrapResult.Success(MasterKey.of(EcdhEs.unwrap(devicePrivateKey, epk, wrappedKey)))
        } catch (e: InvalidCipherTextException) {
            DeviceUnwrapResult.InvalidWrap
        }

    /** `kdfParams.m` on the wire is e.g. `"64MiB"` — "written for humans" per
     * crypto-format.md — while [Argon2Kdf] wants plain KiB. */
    fun parseMemoryKib(m: String): Int {
        val match =
            Regex("^(\\d+)(KiB|MiB|GiB)$").matchEntire(m.trim())
                ?: error("unrecognised kdfParams.m value: '$m'")
        val (value, unit) = match.destructured
        val n = value.toInt()
        return when (unit) {
            "KiB" -> n
            "MiB" -> n * 1024
            "GiB" -> n * 1024 * 1024
            else -> error("unreachable")
        }
    }

    /** The inverse of [parseMemoryKib], for the value this device writes when it
     * enrols a recovery wrapping itself. Always emits MiB — the only unit
     * [Argon2Kdf.DEFAULT_MEMORY_KIB] (64 MiB) needs, and simpler than a general
     * greatest-unit formatter this module has no other use for. */
    fun formatMemoryKib(kib: Int): String {
        require(kib > 0 && kib % 1024 == 0) { "expected a whole number of MiB, got ${kib}KiB" }
        return "${kib / 1024}MiB"
    }
}
