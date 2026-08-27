package fr.enry.archivist.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * ECDH-ES+AES-KW: the Android device wrapping route as of the switch away from
 * RSA-OAEP-256 -- see "Master key wrapping" in crypto-format.md and open question 1
 * in design.md. P-256 only in v1.
 *
 * Confirmed on real hardware before this was written, not assumed: a Keystore-resident
 * RSA-3072 key's Cipher refuses `MGF1-SHA256` on decrypt (`Unsupported MGF1 digest:
 * SHA-256. Only SHA-1 supported`) on at least one real device, while ECDH key agreement
 * against a Keystore-resident EC-P256 key works correctly end-to-end.
 *
 * No explicit `"AndroidKeyStore"` provider is passed to `KeyAgreement.getInstance` —
 * also confirmed live: JCA auto-routes to the Keystore's own ECDH implementation based
 * on the key object's type, the same way `RsaOaep`'s plain `Cipher.getInstance` already
 * does for a Keystore-resident RSA key. That's what makes `unwrap` below usable with
 * *either* a Keystore-resident private key on a real device or a plain one in a test —
 * one code path, not two.
 */
object EcdhEs {
    private const val CURVE = "secp256r1"
    private const val INFO = "archivist:1:ecdh-kek"

    /** [staticPublicKey] is the enrolled device's Keystore public key. Returns the
     * ephemeral public key (SEC1 uncompressed, 65 bytes for P-256 — store this as
     * `epk`) alongside the wrapped key (store as `wrappedKey`). The ephemeral private
     * key is used once and discarded; it is never returned or persisted. */
    fun wrap(
        staticPublicKey: PublicKey,
        plaintext: ByteArray,
    ): Wrapped {
        val ephemeral = generateEphemeralKeyPair()
        val sharedSecret = agree(ephemeral.private, staticPublicKey)
        val epk = encodePoint(ephemeral.public)
        val kek = deriveKek(sharedSecret, epk)
        return Wrapped(epk = epk, wrappedKey = AesKw.wrap(kek, plaintext))
    }

    /** [staticPrivateKey] is the enrolled key — a Keystore-resident `PrivateKey`
     * object on a real device (from `KeyStore.getKey(alias, null)`, never raw bytes;
     * that key never leaves the Keystore provider), or a plain one in a test. [epk]
     * is the SEC1-uncompressed ephemeral public key stored alongside the wrapping. */
    fun unwrap(
        staticPrivateKey: PrivateKey,
        epk: ByteArray,
        wrappedKey: ByteArray,
    ): ByteArray {
        val ephemeralPublicKey = decodePoint(epk)
        val sharedSecret = agree(staticPrivateKey, ephemeralPublicKey)
        val kek = deriveKek(sharedSecret, epk)
        return AesKw.unwrap(kek, wrappedKey)
    }

    data class Wrapped(val epk: ByteArray, val wrappedKey: ByteArray)

    private fun deriveKek(
        sharedSecret: ByteArray,
        epk: ByteArray,
    ): ByteArray = Hkdf.sha256(ikm = sharedSecret, salt = epk, info = INFO.toByteArray(Charsets.US_ASCII), length = 32)

    private fun agree(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        return generator.generateKeyPair()
    }

    /** SEC1 uncompressed point encoding (`0x04 || X || Y`) — what
     * `PublicKey.getEncoded()` does *not* give directly (that's X.509
     * SubjectPublicKeyInfo, curve OID and all); Java has no built-in "just the point"
     * accessor, so this and [decodePoint] round-trip through the field size by hand.
     * `internal` (not `private`) so the conformance test can reuse the exact same
     * encoding rather than risk a second, drifting implementation of it. */
    internal fun encodePoint(publicKey: PublicKey): ByteArray {
        val ecPublicKey = publicKey as ECPublicKey
        val fieldSizeBytes = (ecPublicKey.params.curve.field.fieldSize + 7) / 8
        val x = ecPublicKey.w.affineX.toFixedLengthBytes(fieldSizeBytes)
        val y = ecPublicKey.w.affineY.toFixedLengthBytes(fieldSizeBytes)
        return byteArrayOf(0x04) + x + y
    }

    internal fun decodePoint(epk: ByteArray): PublicKey {
        require(epk.size == 65 && epk[0] == 0x04.toByte()) { "epk must be a 65-byte SEC1 uncompressed P-256 point" }
        val fieldSizeBytes = 32
        val x = BigInteger(1, epk.copyOfRange(1, 1 + fieldSizeBytes))
        val y = BigInteger(1, epk.copyOfRange(1 + fieldSizeBytes, 1 + 2 * fieldSizeBytes))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256ParameterSpec()))
    }

    /** A plain (non-Keystore) private key from a raw scalar — used to reconstruct a
     * conformance vector's fixed test keys. Never how a real device's static key is
     * built; that one is Keystore-resident from the moment it's generated. */
    internal fun privateKeyFromScalar(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, p256ParameterSpec()))

    internal fun p256ParameterSpec(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(CURVE))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun BigInteger.toFixedLengthBytes(length: Int): ByteArray {
        val raw = toByteArray() // two's-complement, may carry a leading 0x00 sign byte or be short
        val trimmed = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
        return if (trimmed.size == length) trimmed else ByteArray(length - trimmed.size) + trimmed
    }
}
