package fr.enry.archivist.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA-OAEP-256: SHA-256 digest **and** MGF1-SHA-256, explicit. The Android provider's
 * `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` silently defaults MGF1 to SHA-1, so the spec
 * (and RFC 3394-style ecosystem this sits in) requires passing the parameter spec by
 * hand -- see crypto-format.md's "Master key wrapping, Android" section.
 */
object RsaOaep {
    private val SPEC = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT,
    )

    fun wrap(publicKey: PublicKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, SPEC)
        return cipher.doFinal(plaintext)
    }

    /** Throws on a wrong key or a tampered ciphertext (OAEP padding/label check). */
    fun unwrap(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, SPEC)
        return cipher.doFinal(ciphertext)
    }
}
