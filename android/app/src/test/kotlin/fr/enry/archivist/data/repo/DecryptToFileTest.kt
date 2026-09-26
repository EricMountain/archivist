package fr.enry.archivist.data.repo

import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.StreamingCipher
import fr.enry.archivist.crypto.WholeObjectCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [decryptToFile] is the memory-bounded half of `downloadOriginalToFile`, which repair and "View
 * original" use for videos. The real download can't be mocked (its URL is a hardcoded `https://`),
 * so this covers the part that actually decides whether a big video fits in memory: streaming
 * ciphertext through the real [StreamingCipher] into a file.
 */
class DecryptToFileTest {
    private lateinit var dir: File
    private lateinit var target: File

    private val dek = ByteArray(32) { (it + 3).toByte() }
    private val aad = Aad.of("01ARZ3NDEKTSV4RRFFQ69G5FAV", ObjectRef.Rendition("r1"))

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("decrypt-to-file-test").toFile()
        target = File(dir, "out.bin")
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun plaintext(size: Int) = ByteArray(size) { (it * 31 + it / 251).toByte() }

    private fun streamEncrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        StreamingCipher.encryptingStream(dek, aad, out).use { it.write(plain) }
        return out.toByteArray()
    }

    @Test
    fun `a multi-segment streamed payload decrypts to exactly the original bytes`() {
        val plain = plaintext(3_500_000) // > 3 x 1 MiB segments, with a partial final one
        decryptToFile(ByteArrayInputStream(streamEncrypt(plain)), dek, aad, encChunkSize = 1_048_576, encIv = null, target = target)

        assertEquals(plain.size.toLong(), target.length())
        assertArrayEquals(plain, target.readBytes())
    }

    @Test
    fun `an empty streamed payload gives an empty file`() {
        decryptToFile(ByteArrayInputStream(streamEncrypt(ByteArray(0))), dek, aad, 1_048_576, null, target)
        assertEquals(0L, target.length())
    }

    @Test
    fun `a tampered stream is rejected`() {
        val cipher = streamEncrypt(plaintext(2_200_000))
        cipher[cipher.size / 2] = (cipher[cipher.size / 2].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            decryptToFile(ByteArrayInputStream(cipher), dek, aad, 1_048_576, null, target)
        }
    }

    @Test
    fun `a stream truncated mid-way is rejected, not silently accepted as a shorter video`() {
        val cipher = streamEncrypt(plaintext(3_500_000))

        assertThrows(Exception::class.java) {
            decryptToFile(ByteArrayInputStream(cipher.copyOf(cipher.size - 400_000)), dek, aad, 1_048_576, null, target)
        }
    }

    @Test
    fun `the wrong object context fails authentication`() {
        val cipher = streamEncrypt(plaintext(1_500_000))
        val wrongAad = Aad.of("01ARZ3NDEKTSV4RRFFQ69G5FAW", ObjectRef.Rendition("r1"))

        assertThrows(Exception::class.java) {
            decryptToFile(ByteArrayInputStream(cipher), dek, wrongAad, 1_048_576, null, target)
        }
    }

    @Test
    fun `a whole-object rendition decrypts to a file`() {
        val plain = plaintext(20_000)
        val iv = EnvelopeCrypto.generateIv()
        val cipher = WholeObjectCipher.encrypt(dek, iv, aad, plain)

        decryptToFile(ByteArrayInputStream(cipher), dek, aad, 0L, Base64.getEncoder().encodeToString(iv), target)

        assertArrayEquals(plain, target.readBytes())
    }

    @Test
    fun `a whole-object rendition with no IV is an error`() {
        assertThrows(IOException::class.java) {
            decryptToFile(ByteArrayInputStream(ByteArray(64)), dek, aad, 0L, null, target)
        }
    }

    @Test
    fun `a whole-object rendition larger than the format allows is refused without buffering it all`() {
        var produced = 0L
        // An endless source: if the bound weren't enforced this would run until out of memory.
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    produced++
                    return 7
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    produced += len
                    java.util.Arrays.fill(b, off, off + len, 7)
                    return len
                }
            }

        assertThrows(IOException::class.java) {
            decryptToFile(endless, dek, aad, 0L, Base64.getEncoder().encodeToString(ByteArray(12)), target)
        }
        assertTrue(produced < 40L * 1024 * 1024, "read $produced bytes before giving up")
    }

    @Test
    fun `a streamed payload is copied without needing the whole ciphertext at once`() {
        // A source that only ever hands out small pieces, as a network body does.
        val cipher = streamEncrypt(plaintext(2_600_000))
        val trickle =
            object : InputStream() {
                private var pos = 0

                override fun read(): Int = if (pos < cipher.size) cipher[pos++].toInt() and 0xFF else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (pos >= cipher.size) return -1
                    val n = minOf(len, 4096, cipher.size - pos)
                    System.arraycopy(cipher, pos, b, off, n)
                    pos += n
                    return n
                }
            }

        decryptToFile(trickle, dek, aad, 1_048_576, null, target)

        assertEquals(2_600_000L, target.length())
    }
}
