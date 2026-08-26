package fr.enry.archivist.debug

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "KeystoreSpike"
private const val KEYSTORE = "AndroidKeyStore"

/**
 * Plan step 2.4a — a **measurement, not a feature**. Settles open question 1 in
 * `design.md` with real numbers: can this device generate an RSA-3072 key inside
 * StrongBox, and does OAEP-SHA256/MGF1-SHA256 wrapping actually work against it?
 *
 * **Delete this file once the result is recorded in `design.md`.**
 *
 * Must run on a real device, not an emulator — emulators have no StrongBox, so an
 * emulator run answers nothing. Repeat on the oldest phone in the target set, since
 * StrongBox algorithm support varies by device and OS version.
 *
 * **Not run in this session: no device or emulator is attached to this environment.**
 * Written and ready to run (`./gradlew :app:connectedDebugAndroidTest`), but the
 * numbers this is supposed to produce do not exist yet — don't treat open question 1
 * as closed based on this file's existence.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSpike {
    private data class Attempt(
        val alias: String,
        val algorithm: String,
        val requestedStrongBox: Boolean,
        val outcome: String,
        val elapsedMs: Long,
        val actualSecurityLevel: String?,
    )

    @Test
    fun measureKeystoreAlgorithmSupport() {
        val results = mutableListOf<Attempt>()

        for (algorithm in listOf("RSA-3072", "RSA-2048", "EC-P256")) {
            for (strongBox in listOf(true, false)) {
                results += attemptGeneration(algorithm, strongBox)
            }
        }

        Log.i(TAG, "===== KeystoreSpike results =====")
        for (r in results) {
            Log.i(
                TAG,
                "${r.algorithm} strongBox=${r.requestedStrongBox} -> ${r.outcome} " +
                    "(${r.elapsedMs}ms, actual=${r.actualSecurityLevel})",
            )
        }
        Log.i(TAG, "==================================")

        // The one result 2.5 actually depends on: does RSA-3072 wrap/unwrap correctly
        // with the exact parameters crypto-format.md specifies? Only meaningful if
        // some RSA-3072 attempt above actually produced a key.
        val rsa3072 = results.firstOrNull { it.algorithm == "RSA-3072" && it.outcome == "generated" }
        if (rsa3072 != null) {
            verifyOaepWrapUnwrap("rsa3072-wrap-test")
        } else {
            Log.w(TAG, "No RSA-3072 key generated on this device at all — skipping the wrap/unwrap check.")
        }
    }

    private fun attemptGeneration(
        algorithm: String,
        strongBox: Boolean,
    ): Attempt {
        val alias = "keystore-spike-${algorithm.lowercase()}-sb$strongBox"
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias) // in case a previous run left it behind

        val start = System.nanoTime()
        return try {
            val generator: KeyPairGenerator
            val specBuilder: KeyGenParameterSpec.Builder

            when (algorithm) {
                "RSA-3072", "RSA-2048" -> {
                    generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
                    val keySize = if (algorithm == "RSA-3072") 3072 else 2048
                    specBuilder =
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                            .setKeySize(keySize)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                }

                "EC-P256" -> {
                    generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                    specBuilder =
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                            .setKeySize(256)
                }

                else -> error("unreachable: $algorithm")
            }

            specBuilder.setIsStrongBoxBacked(strongBox)
            generator.initialize(specBuilder.build())
            val keyPair = generator.generateKeyPair()
            val elapsed = (System.nanoTime() - start) / 1_000_000

            val factory =
                KeyFactory.getInstance(
                    if (algorithm == "EC-P256") KeyProperties.KEY_ALGORITHM_EC else KeyProperties.KEY_ALGORITHM_RSA,
                    KEYSTORE,
                )
            val keyInfo = factory.getKeySpec(keyPair.private, KeyInfo::class.java)
            val securityLevel =
                when (keyInfo.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                    else -> "UNKNOWN(${keyInfo.securityLevel})"
                }

            Attempt(alias, algorithm, strongBox, "generated", elapsed, securityLevel)
        } catch (e: StrongBoxUnavailableException) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            Attempt(alias, algorithm, strongBox, "StrongBoxUnavailableException", elapsed, null)
        } catch (e: Exception) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            Attempt(alias, algorithm, strongBox, "FAILED: ${e::class.simpleName}: ${e.message}", elapsed, null)
        } finally {
            keyStore.deleteEntry(alias)
        }
    }

    /**
     * The exact parameters `crypto-format.md` specifies for wrapAlg RSA-OAEP-256: OAEP
     * with SHA-256 **and MGF1-SHA-256 passed explicitly**. Android's RSA/ECB/OAEPPadding
     * defaults MGF1 to SHA-1 despite the cipher name — that footgun is exactly what this
     * check exists to catch before 2.5 depends on it.
     */
    private fun verifyOaepWrapUnwrap(alias: String) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias)
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            val keyPair = generator.generateKeyPair()

            val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

            val plaintext = ByteArray(32) { it.toByte() } // stand-in 256-bit master key

            val encryptCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            encryptCipher.init(Cipher.ENCRYPT_MODE, keyPair.public, oaepSpec)
            val wrapped = encryptCipher.doFinal(plaintext)

            val decryptCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            decryptCipher.init(Cipher.DECRYPT_MODE, keyPair.private, oaepSpec)
            val unwrapped = decryptCipher.doFinal(wrapped)

            val matches = plaintext.contentEquals(unwrapped)
            Log.i(TAG, "OAEP-SHA256/MGF1-SHA256 wrap/unwrap round-trip: ${if (matches) "OK" else "MISMATCH"}")
            check(matches) { "RSA-3072 OAEP round-trip produced different bytes back" }
        } finally {
            keyStore.deleteEntry(alias)
        }
    }
}
