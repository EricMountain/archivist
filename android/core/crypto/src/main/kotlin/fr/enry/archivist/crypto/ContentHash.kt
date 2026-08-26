package fr.enry.archivist.crypto

import java.io.InputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dedup hash of the plaintext rendition: `hmac-sha256:<64 lowercase hex chars>`, keyed
 * so table access alone can't confirm whether a known image is in the library.
 * Streamed, never buffers the whole rendition.
 */
object ContentHash {
    private const val PREFIX = "hmac-sha256:"

    fun of(hashSecret: ByteArray, plaintext: InputStream): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hashSecret, "HmacSHA256"))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = plaintext.read(buffer)
            if (n < 0) break
            mac.update(buffer, 0, n)
        }
        return PREFIX + mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
