package fr.enry.archivist.data.repo

import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.domain.ExifBlob
import kotlinx.serialization.json.Json

/** An encrypted [ExifBlob]: `exifEnc`'s ciphertext and `exifIv`, raw (callers base64 them). */
class EncryptedExif(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * `exifEnc`'s one encoding (crypto-format.md: whole-object mode, the asset's DEK, a fresh
 * IV, AAD `archivist:1:<photoId>:x`). Upload ([UploadRepository]) encrypts with it, the
 * detail screen ([PhotoDetailRepository]) decrypts with it, and "Update location"
 * ([LocationUpdateRepository]) re-encrypts a blob that gained coordinates with it, so the
 * three can't drift apart.
 */
internal object ExifCrypto {
    private val json = Json { ignoreUnknownKeys = true }

    fun encrypt(
        dek: ByteArray,
        photoId: String,
        blob: ExifBlob,
    ): EncryptedExif {
        val iv = EnvelopeCrypto.generateIv()
        val ciphertext =
            WholeObjectCipher.encrypt(
                dek,
                iv,
                Aad.of(photoId, ObjectRef.Exif),
                json.encodeToString(ExifBlob.serializer(), blob).toByteArray(Charsets.UTF_8),
            )
        return EncryptedExif(iv, ciphertext)
    }

    /** Throws on a wrong key, a tampered blob or one that doesn't decode. */
    fun decrypt(
        dek: ByteArray,
        photoId: String,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ExifBlob {
        val plaintext = WholeObjectCipher.decrypt(dek, iv, Aad.of(photoId, ObjectRef.Exif), ciphertext)
        return json.decodeFromString(ExifBlob.serializer(), plaintext.toString(Charsets.UTF_8))
    }
}
