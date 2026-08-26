package fr.enry.archivist.crypto

import java.security.MessageDigest

/**
 * Reconstructs the deterministic plaintext used for vector cases that omit a `.plain`
 * file, per manifest.json's `plainPatternSpec`: block i = SHA256(seed || be32(i)),
 * concatenated and truncated to length. Mirrors tools/gen-vectors/generate.py exactly.
 */
object VectorPattern {
    fun bytes(seed: String, length: Int): ByteArray {
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val out = ByteArray(length)
        var written = 0
        var counter = 0
        while (written < length) {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(seedBytes)
                update(byteArrayOf((counter shr 24).toByte(), (counter shr 16).toByte(), (counter shr 8).toByte(), counter.toByte()))
            }.digest()
            val n = minOf(digest.size, length - written)
            System.arraycopy(digest, 0, out, written, n)
            written += n
            counter++
        }
        return out
    }
}
