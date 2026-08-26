package fr.enry.archivist.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** HKDF-SHA256, used standalone for the passkey KEK (the streaming cipher's HKDF use
 * is internal to Tink and never called directly). */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, if (salt.isEmpty()) null else salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }
}
