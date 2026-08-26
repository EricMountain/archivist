package fr.enry.archivist.crypto

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.proto.AesGcmHkdfStreamingKey
import com.google.crypto.tink.proto.AesGcmHkdfStreamingParams
import com.google.crypto.tink.proto.HashType
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.shaded.protobuf.ByteString
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.InputStream
import java.io.OutputStream

/**
 * Mode B of crypto-format.md: byte-for-byte Tink's `AES256_GCM_HKDF_1MB`, used
 * directly rather than reimplemented -- this class only supplies the DEK, which Tink's
 * own key generation can't do since the DEK comes from unwrapping, not from Tink.
 */
object StreamingCipher {
    const val SEGMENT_SIZE = 1_048_576
    const val HEADER_LEN = 40
    const val TAG_LEN = 16
    const val DERIVED_KEY_SIZE = 32

    /** Plaintext capacity of the first ciphertext segment (header is charged to it). */
    const val C0 = SEGMENT_SIZE - HEADER_LEN - TAG_LEN

    /** Plaintext capacity of every later ciphertext segment. */
    const val CN = SEGMENT_SIZE - TAG_LEN

    init {
        StreamingAeadConfig.register()
    }

    fun segmentCount(plainLength: Long): Long =
        if (plainLength <= C0) 1L else 1L + ((plainLength - C0) + CN - 1) / CN

    fun ciphertextLength(plainLength: Long): Long =
        HEADER_LEN + plainLength + TAG_LEN * segmentCount(plainLength)

    private fun handle(dek: ByteArray): KeysetHandle {
        require(dek.size == DERIVED_KEY_SIZE) { "DEK must be $DERIVED_KEY_SIZE bytes" }
        val keyProto = AesGcmHkdfStreamingKey.newBuilder()
            .setVersion(0)
            .setParams(
                AesGcmHkdfStreamingParams.newBuilder()
                    .setCiphertextSegmentSize(SEGMENT_SIZE)
                    .setDerivedKeySize(DERIVED_KEY_SIZE)
                    .setHkdfHashType(HashType.SHA256),
            )
            .setKeyValue(ByteString.copyFrom(dek))
            .build()
        val keyData = KeyData.newBuilder()
            .setTypeUrl("type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey")
            .setValue(keyProto.toByteString())
            .setKeyMaterialType(KeyData.KeyMaterialType.SYMMETRIC)
            .build()
        val key = Keyset.Key.newBuilder()
            .setKeyData(keyData)
            .setStatus(KeyStatusType.ENABLED)
            .setKeyId(1)
            .setOutputPrefixType(OutputPrefixType.RAW)
            .build()
        val keyset = Keyset.newBuilder().setPrimaryKeyId(1).addKey(key).build()
        return CleartextKeysetHandle.fromKeyset(keyset)
    }

    fun encryptingStream(dek: ByteArray, aad: ByteArray, sink: OutputStream): OutputStream =
        handle(dek).getPrimitive(StreamingAead::class.java).newEncryptingStream(sink, aad)

    /** Throws on truncation, reordering, duplication, or any AAD/key mismatch. */
    fun decryptingStream(dek: ByteArray, aad: ByteArray, source: InputStream): InputStream =
        handle(dek).getPrimitive(StreamingAead::class.java).newDecryptingStream(source, aad)
}
