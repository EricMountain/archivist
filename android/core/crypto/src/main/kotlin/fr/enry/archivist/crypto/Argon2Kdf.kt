package fr.enry.archivist.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id v1.3, per crypto-format.md's recovery-code KEK derivation. */
object Argon2Kdf {
    const val DEFAULT_MEMORY_KIB = 65536
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 1
    const val DEFAULT_LENGTH = 32

    fun kek(
        password: ByteArray,
        salt: ByteArray,
        memoryKib: Int = DEFAULT_MEMORY_KIB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
        length: Int = DEFAULT_LENGTH,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKib)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(length)
        generator.generateBytes(password, out)
        return out
    }
}
