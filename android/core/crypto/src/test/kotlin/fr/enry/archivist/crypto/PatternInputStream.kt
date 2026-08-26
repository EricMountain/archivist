package fr.enry.archivist.crypto

import java.io.InputStream
import java.security.MessageDigest

/**
 * Same deterministic content as [VectorPattern], but generated on demand -- so a
 * multi-hundred-MB "file" can be streamed through encryption without ever holding more
 * than one 32-byte block (plus I/O buffers) in memory at once.
 */
class PatternInputStream(seed: String, private val length: Long) : InputStream() {
    private val seedBytes = seed.toByteArray(Charsets.UTF_8)
    private var produced = 0L
    private var block = ByteArray(0)
    private var blockPos = 0
    private var counter = 0

    private fun nextBlock(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(seedBytes)
            update(byteArrayOf((counter shr 24).toByte(), (counter shr 16).toByte(), (counter shr 8).toByte(), counter.toByte()))
        }.digest()
        counter++
        return digest
    }

    override fun read(): Int {
        if (produced >= length) return -1
        if (blockPos >= block.size) {
            block = nextBlock()
            blockPos = 0
        }
        produced++
        return block[blockPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (produced >= length) return -1
        var written = 0
        while (written < len && produced < length) {
            if (blockPos >= block.size) {
                block = nextBlock()
                blockPos = 0
            }
            val n = minOf(len - written, block.size - blockPos, (length - produced).toInt())
            System.arraycopy(block, blockPos, b, off + written, n)
            blockPos += n
            written += n
            produced += n
        }
        return if (written == 0) -1 else written
    }
}
