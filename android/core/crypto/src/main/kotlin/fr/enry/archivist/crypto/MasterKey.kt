package fr.enry.archivist.crypto

/**
 * The master key: 256 bits, random, per owner, and never allowed to leave a client in
 * plaintext. This holder is the only place the raw bytes live -- an in-memory,
 * non-exportable key that is never written to disk or logged. Call [clear] from
 * `onTrimMemory` and whenever the app locks.
 */
class MasterKey private constructor(private var bytes: ByteArray?) {
    val isPresent: Boolean
        get() = bytes != null

    fun wrapDek(dek: ByteArray): ByteArray =
        EnvelopeCrypto.wrapDek(requireKey(), dek)

    fun unwrapDek(encDek: ByteArray): ByteArray =
        EnvelopeCrypto.unwrapDek(requireKey(), encDek)

    fun clear() {
        bytes?.fill(0)
        bytes = null
    }

    private fun requireKey(): ByteArray =
        bytes ?: error("master key is not available -- app is locked")

    companion object {
        fun of(rawKey: ByteArray): MasterKey {
            require(rawKey.size == 32) { "master key must be 32 bytes" }
            return MasterKey(rawKey.copyOf())
        }
    }
}
