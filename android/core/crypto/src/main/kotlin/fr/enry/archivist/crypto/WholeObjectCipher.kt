package fr.enry.archivist.crypto

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mode A of crypto-format.md: AES-256-GCM, a 12-byte random IV, a 16-byte tag appended
 * to the ciphertext. Used directly, streamed through [CipherInputStream]/
 * [CipherOutputStream] so a caller never has to hold a whole object in memory.
 */
object WholeObjectCipher {
    const val IV_LEN = 12
    const val TAG_LEN_BYTES = 16
    private const val TAG_LEN_BITS = TAG_LEN_BYTES * 8
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun encryptingStream(dek: ByteArray, iv: ByteArray, aad: ByteArray, sink: OutputStream): OutputStream {
        require(iv.size == IV_LEN) { "IV must be $IV_LEN bytes" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        return CipherOutputStream(sink, cipher)
    }

    fun decryptingStream(dek: ByteArray, iv: ByteArray, aad: ByteArray, source: InputStream): InputStream {
        require(iv.size == IV_LEN) { "IV must be $IV_LEN bytes" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        return CipherInputStream(source, cipher)
    }

    fun encrypt(dek: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Throws [javax.crypto.AEADBadTagException] (or similar) on any tamper. */
    fun decrypt(dek: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
