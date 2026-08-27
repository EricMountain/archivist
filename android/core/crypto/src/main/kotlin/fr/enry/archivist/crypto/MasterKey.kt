package fr.enry.archivist.crypto

import java.security.PublicKey
import java.security.SecureRandom

/**
 * The master key: 256 bits, random, per owner, and never allowed to leave a client in
 * plaintext. This holder is the only place the raw bytes live -- an in-memory,
 * non-exportable key that is never written to disk or logged. Call [clear] from
 * `onTrimMemory` and whenever the app locks.
 */
class MasterKey private constructor(private var bytes: ByteArray?) {
    val isPresent: Boolean
        get() = bytes != null

    fun wrapDek(dek: ByteArray): ByteArray =
        EnvelopeCrypto.wrapDek(requireKey(), dek)

    fun unwrapDek(encDek: ByteArray): ByteArray =
        EnvelopeCrypto.unwrapDek(requireKey(), encDek)

    /** `encHashSecret = AES-KW(kek = master key, key = hashSecret)` — see
     * "`contentHash` is HMAC'd" in crypto-format.md. Same operation as [wrapDek]; a
     * separate name because a hash secret and a DEK play very different roles even
     * though the wrap is identical. */
    fun wrapHashSecret(hashSecret: ByteArray): ByteArray =
        EnvelopeCrypto.wrapDek(requireKey(), hashSecret)

    fun unwrapHashSecret(encHashSecret: ByteArray): ByteArray =
        EnvelopeCrypto.unwrapDek(requireKey(), encHashSecret)

    /** Wraps this master key to a device's Keystore public key via ECDH-ES+AES-KW —
     * the only place raw master-key bytes are read for this, whether the key was just
     * generated (first enrolment) or recovered via [Argon2Kdf] (a later device / a
     * lock-screen-invalidation re-enrolment). See "Master key wrapping" in
     * crypto-format.md. */
    fun wrapForDevice(devicePublicKey: PublicKey): EcdhEs.Wrapped = EcdhEs.wrap(devicePublicKey, requireKey())

    /** Wraps this master key under a fresh Argon2id KEK derived from [entropy] (the
     * recovery code's entropy field, not the full code with its check symbol — see
     * "Verification" in crypto-format.md). A fresh [kdfSalt] is drawn every call, so
     * re-running this (e.g. rotation) never reuses a salt. */
    fun wrapForRecovery(
        entropy: String,
        memoryKib: Int = Argon2Kdf.DEFAULT_MEMORY_KIB,
        iterations: Int = Argon2Kdf.DEFAULT_ITERATIONS,
        parallelism: Int = Argon2Kdf.DEFAULT_PARALLELISM,
    ): KeyCustody.RecoveryWrap {
        val kdfSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kek = Argon2Kdf.kek(entropy.toByteArray(Charsets.US_ASCII), kdfSalt, memoryKib, iterations, parallelism)
        return KeyCustody.RecoveryWrap(kdfSalt, memoryKib, iterations, parallelism, AesKw.wrap(kek, requireKey()))
    }

    fun clear() {
        bytes?.fill(0)
        bytes = null
    }

    private fun requireKey(): ByteArray =
        bytes ?: error("master key is not available -- app is locked")

    companion object {
        fun of(rawKey: ByteArray): MasterKey {
            require(rawKey.size == 32) { "master key must be 32 bytes" }
            return MasterKey(rawKey.copyOf())
        }
    }
}
