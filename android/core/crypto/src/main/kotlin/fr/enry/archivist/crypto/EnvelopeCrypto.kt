package fr.enry.archivist.crypto

import java.security.SecureRandom

/** DEK generation and per-object IVs. A DEK is never reused across assets; an IV is
 * freshly generated per object and stored per object, never derived from anything
 * that repeats. */
object EnvelopeCrypto {
    private val random = SecureRandom()

    fun generateDek(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun generateIv(): ByteArray = ByteArray(WholeObjectCipher.IV_LEN).also { random.nextBytes(it) }

    /** `AES-KW(kek = master key, key = DEK)`, 40 bytes. */
    fun wrapDek(masterKey: ByteArray, dek: ByteArray): ByteArray = AesKw.wrap(masterKey, dek)

    fun unwrapDek(masterKey: ByteArray, encDek: ByteArray): ByteArray = AesKw.unwrap(masterKey, encDek)
}
