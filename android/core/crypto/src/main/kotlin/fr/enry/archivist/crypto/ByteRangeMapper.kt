package fr.enry.archivist.crypto

/**
 * The plaintext-range -> ciphertext-range arithmetic from crypto-format.md's
 * "Byte-range mapping". Pure arithmetic only -- reading an arbitrary ciphertext range
 * back into plaintext needs a segment-level decryptor, which video seeking (out of
 * MVP scope; see plan 02) will need and this module doesn't build yet.
 */
object ByteRangeMapper {
    /** (segment index, offset within that segment's plaintext) for plaintext byte [p]. */
    fun segmentFor(p: Long): Pair<Long, Long> {
        val c0 = StreamingCipher.C0.toLong()
        val cn = StreamingCipher.CN.toLong()
        return if (p < c0) {
            0L to p
        } else {
            val i = 1 + (p - c0) / cn
            val off = (p - c0) % cn
            i to off
        }
    }

    /** (cipherStart, cipherEndInclusive, trimFrontBytes) to serve plaintext range [a, b]. */
    fun cipherRangeFor(a: Long, b: Long, totalCipherLen: Long): Triple<Long, Long, Long> {
        val (iA, offA) = segmentFor(a)
        val (iB, _) = segmentFor(b)
        val segment = StreamingCipher.SEGMENT_SIZE.toLong()
        val start = iA * segment
        val end = minOf((iB + 1) * segment, totalCipherLen) - 1
        return Triple(start, end, offA)
    }
}
