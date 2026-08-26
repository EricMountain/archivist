package fr.enry.archivist.crypto

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Plan 02 step 2.2's "Done when": a 100 MB round-trip runs without ever holding the
 * whole object in memory. Content and ciphertext are both generated/consumed through
 * streams -- never a byte array the size of the file -- and compared by digest.
 */
class LargeRoundTripTest {
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `100 MB streams through encryption and decryption intact`() {
        val length = 100L * 1024 * 1024
        val dek = EnvelopeCrypto.generateDek()
        val aad = Aad.of("01K5A2Q8ZCV1D9KXM3BQNR7T2F", ObjectRef.Rendition("01K5A2Q8ZCW4MB7XKQNV2HTRF3"))

        val cipherFile = File.createTempFile("archivist-100mb", ".cipher").also { tempFiles += it }

        val plainDigest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(cipherFile).use { fos ->
            StreamingCipher.encryptingStream(dek, aad, fos).use { enc ->
                DigestInputStream(PatternInputStream("large-round-trip", length), plainDigest).copyTo(enc)
            }
        }

        val decryptedDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(cipherFile).use { fis ->
            StreamingCipher.decryptingStream(dek, aad, fis).use { dec ->
                DigestOutputStream(OutputStream.nullOutputStream(), decryptedDigest).use { dos -> dec.copyTo(dos) }
            }
        }

        assertArrayEquals(plainDigest.digest(), decryptedDigest.digest())
    }

    @Test
    fun `a truncated large ciphertext fails to decrypt`() {
        val length = 40L * 1024 * 1024
        val dek = EnvelopeCrypto.generateDek()
        val aad = Aad.of("01K5A2Q8ZCV1D9KXM3BQNR7T2F", ObjectRef.Exif)

        val cipherFile = File.createTempFile("archivist-truncated", ".cipher").also { tempFiles += it }
        FileOutputStream(cipherFile).use { fos ->
            StreamingCipher.encryptingStream(dek, aad, fos).use { enc ->
                PatternInputStream("truncated", length).copyTo(enc)
            }
        }

        val truncated = cipherFile.readBytes().copyOf(cipherFile.length().toInt() - 4096)

        assertThrows(Exception::class.java) {
            StreamingCipher.decryptingStream(dek, aad, truncated.inputStream()).use { it.copyTo(OutputStream.nullOutputStream()) }
        }
    }
}
