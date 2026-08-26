package fr.enry.archivist.crypto

import java.io.InputStream
import java.io.OutputStream

/**
 * Chooses whole-object vs. streaming encryption per crypto-format.md's `encChunkSize`
 * rule, and streams either way -- a caller never holds a full object in memory, so a
 * 480 MB video encrypts through an [InputStream] with the heap barely moving.
 *
 * The threshold is pure client policy: it is recorded per object (`encChunkSize`), so
 * retuning it later never invalidates an existing object.
 */
object ObjectCodec {
    const val DEFAULT_THRESHOLD_BYTES = 32L * 1024 * 1024 // 32 MiB

    enum class Mode(val encChunkSize: Int) {
        WHOLE(0),
        STREAMING(StreamingCipher.SEGMENT_SIZE),
    }

    fun modeFor(plainLength: Long, thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES): Mode =
        if (plainLength > thresholdBytes) Mode.STREAMING else Mode.WHOLE

    /** Whole-object mode also needs the per-object IV; streaming mode carries its own
     * salt/nonce prefix in the ciphertext header, so no separate IV is stored. */
    fun encrypt(
        mode: Mode,
        dek: ByteArray,
        iv: ByteArray?,
        aad: ByteArray,
        plaintext: InputStream,
        sink: OutputStream,
    ) {
        when (mode) {
            Mode.WHOLE -> {
                requireNotNull(iv) { "whole-object mode requires an IV" }
                WholeObjectCipher.encryptingStream(dek, iv, aad, sink).use { plaintext.copyTo(it) }
            }
            Mode.STREAMING -> {
                StreamingCipher.encryptingStream(dek, aad, sink).use { plaintext.copyTo(it) }
            }
        }
    }

    fun decrypt(
        mode: Mode,
        dek: ByteArray,
        iv: ByteArray?,
        aad: ByteArray,
        ciphertext: InputStream,
        sink: OutputStream,
    ) {
        when (mode) {
            Mode.WHOLE -> {
                requireNotNull(iv) { "whole-object mode requires an IV" }
                WholeObjectCipher.decryptingStream(dek, iv, aad, ciphertext).use { it.copyTo(sink) }
            }
            Mode.STREAMING -> {
                StreamingCipher.decryptingStream(dek, aad, ciphertext).use { it.copyTo(sink) }
            }
        }
    }
}
