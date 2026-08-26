package fr.enry.archivist.crypto

import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.params.KeyParameter

/** RFC 3394 AES Key Wrap: a 256-bit KEK, a 256-bit key, a 40-byte output. Deterministic. */
object AesKw {
    fun wrap(kek: ByteArray, key: ByteArray): ByteArray {
        val engine = AESWrapEngine()
        engine.init(true, KeyParameter(kek))
        return engine.wrap(key, 0, key.size)
    }

    /** Throws (BC's `InvalidCipherTextException`) if `kek` is wrong or `wrapped` is tampered. */
    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
        val engine = AESWrapEngine()
        engine.init(false, KeyParameter(kek))
        return engine.unwrap(wrapped, 0, wrapped.size)
    }
}
