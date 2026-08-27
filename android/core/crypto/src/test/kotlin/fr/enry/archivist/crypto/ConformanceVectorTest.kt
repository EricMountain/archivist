package fr.enry.archivist.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * "Every client's test suite decrypts these fixtures. A client that cannot is broken,
 * regardless of what its own round-trip tests say." -- crypto-format.md.
 *
 * Reads testdata/vectors/manifest.json (repo root) and runs every case in it.
 */
class ConformanceVectorTest {

    private val vectorsDir: File by lazy {
        val configured = System.getProperty("archivist.vectorsDir")
        val dir = if (configured != null) {
            File(configured)
        } else {
            // Fallback for running outside Gradle (e.g. from an IDE run config):
            // core/crypto/src/test/kotlin/... -> repo root is five levels up.
            File("").absoluteFile.parentFile?.parentFile?.resolve("testdata/vectors")
                ?: error("cannot locate testdata/vectors")
        }
        check(dir.isDirectory) { "vectors dir not found: $dir -- run tools/gen-vectors/generate.py first" }
        dir
    }

    private fun manifest(): JSONObject =
        JSONObject(File(vectorsDir, "manifest.json").readText())

    private fun file(name: String): ByteArray = File(vectorsDir, name).readBytes()

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
        }
        return out
    }

    private fun assertThrows(caseId: String, block: () -> Unit) {
        try {
            block()
            fail("case $caseId: expected decryption to fail, but it succeeded")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `all conformance vectors`() {
        val cases = manifest().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            when (case.getString("mode")) {
                "whole" -> runWhole(case)
                "streaming" -> runStreaming(case)
                "aes-kw" -> runAesKw(case)
                "rsa-oaep" -> runRsaOaep(case)
                "argon2id" -> runArgon2id(case)
                "recovery-normalisation" -> runRecoveryNormalisation(case)
                "recovery-checksum" -> runRecoveryChecksum(case)
                "hkdf-passkey" -> runHkdfPasskey(case)
                "byte-range" -> runByteRange(case)
                "ecdh-es" -> runEcdhEs(case)
                else -> fail("unknown vector mode: ${case.getString("mode")}")
            }
        }
    }

    private fun runWhole(case: JSONObject) {
        val id = case.getString("id")
        val dek = hex(case.getString("dek"))
        val iv = hex(case.getString("iv"))
        val aad = case.getString("aad").toByteArray(Charsets.UTF_8)
        val cipher = file(case.getJSONObject("files").getString("cipher"))

        when (case.getString("expect")) {
            "decrypt" -> {
                val expected = VectorPattern.bytes(case.getString("plainPatternSeed"), case.getInt("plainLength"))
                val actual = WholeObjectCipher.decrypt(dek, iv, aad, cipher)
                assertArrayEquals(id, expected, actual)
            }
            "fail" -> assertThrows(id) { WholeObjectCipher.decrypt(dek, iv, aad, cipher) }
            else -> fail("case $id: unknown expect")
        }
    }

    private fun runStreaming(case: JSONObject) {
        val id = case.getString("id")
        val dek = hex(case.getString("dek"))
        val aad = case.getString("aad").toByteArray(Charsets.UTF_8)
        val cipher = file(case.getJSONObject("files").getString("cipher"))

        when (case.getString("expect")) {
            "decrypt" -> {
                val expected = VectorPattern.bytes(case.getString("plainPatternSeed"), case.getInt("plainLength"))
                val actual = StreamingCipher.decryptingStream(dek, aad, cipher.inputStream()).use { it.readBytes() }
                assertArrayEquals(id, expected, actual)
            }
            "fail" -> assertThrows(id) {
                StreamingCipher.decryptingStream(dek, aad, cipher.inputStream()).use { it.readBytes() }
            }
            else -> fail("case $id: unknown expect")
        }
    }

    private fun runAesKw(case: JSONObject) {
        val id = case.getString("id")
        val kek = hex(case.getString("kek"))
        val plaintextKey = hex(case.getString("plaintextKey"))
        val expectedWrapped = hex(case.getString("expectedWrapped"))

        assertArrayEquals(id, expectedWrapped, AesKw.wrap(kek, plaintextKey))
        assertArrayEquals(id, plaintextKey, AesKw.unwrap(kek, expectedWrapped))
    }

    private fun runRsaOaep(case: JSONObject) {
        val id = case.getString("id")
        val modulus = BigInteger(case.getString("rsaModulus"), 16)
        val privateExponent = BigInteger(case.getString("rsaPrivateExponent"), 16)
        val cipher = file(case.getJSONObject("files").getString("cipher"))
        val expected = hex(case.getString("expectedPlaintext"))

        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(RSAPrivateKeySpec(modulus, privateExponent))
        assertArrayEquals(id, expected, RsaOaep.unwrap(priv, cipher))

        // Round-trip through the public key too, to exercise the encrypt path.
        val publicExponent = BigInteger.valueOf(case.getLong("rsaPublicExponent"))
        val pub = kf.generatePublic(RSAPublicKeySpec(modulus, publicExponent))
        val rewrapped = RsaOaep.wrap(pub, expected)
        assertArrayEquals(id, expected, RsaOaep.unwrap(priv, rewrapped))
    }

    private fun runArgon2id(case: JSONObject) {
        val id = case.getString("id")
        val password = case.getString("password").toByteArray(Charsets.US_ASCII)
        val salt = hex(case.getString("salt"))
        val params = case.getJSONObject("params")
        val expected = hex(case.getString("expectedKek"))

        val actual = Argon2Kdf.kek(
            password,
            salt,
            memoryKib = params.getInt("m"),
            iterations = params.getInt("t"),
            parallelism = params.getInt("p"),
            length = params.getInt("hashLen"),
        )
        assertArrayEquals(id, expected, actual)
    }

    private fun runRecoveryNormalisation(case: JSONObject) {
        val id = case.getString("id")
        val raw = case.getString("rawInput")
        val expectedNormalised = case.getString("expectedNormalised")
        val salt = hex(case.getString("salt"))
        val params = case.getJSONObject("params")
        val expectedKek = hex(case.getString("expectedKek"))

        val normalised = RecoveryCode.normalise(raw)
        assertEquals(id, expectedNormalised, normalised)

        val kek = Argon2Kdf.kek(
            normalised.substring(0, 25).toByteArray(Charsets.US_ASCII),
            salt,
            memoryKib = params.getInt("m"),
            iterations = params.getInt("t"),
            parallelism = params.getInt("p"),
            length = params.getInt("hashLen"),
        )
        assertArrayEquals(id, expectedKek, kek)
    }

    private fun runRecoveryChecksum(case: JSONObject) {
        val id = case.getString("id")
        val subcases = case.getJSONArray("cases")
        for (i in 0 until subcases.length()) {
            val sub = subcases.getJSONObject(i)
            val code = sub.getString("code")
            val result = RecoveryCode.verify(code)
            when (sub.getString("expect")) {
                "accept" -> assertTrue("$id/${sub.getString("label")}: expected accept", result != null)
                "reject" -> assertNull("$id/${sub.getString("label")}: expected reject", result)
                else -> fail("unknown expect in $id/${sub.getString("label")}")
            }
        }
    }

    private fun runHkdfPasskey(case: JSONObject) {
        val id = case.getString("id")
        val prfOutput = hex(case.getString("prfOutput"))
        val info = case.getString("info").toByteArray(Charsets.US_ASCII)
        val expected = hex(case.getString("expectedKek"))

        assertArrayEquals(id, expected, Hkdf.sha256(prfOutput, ByteArray(0), info, 32))
    }

    private fun runEcdhEs(case: JSONObject) {
        val id = case.getString("id")
        val staticPrivateKey = EcdhEs.privateKeyFromScalar(BigInteger(case.getString("staticPrivateScalar"), 16))
        val staticPublicKey = EcdhEs.decodePoint(hex(case.getString("staticPublicKey")))
        val ephemeralPrivateKey = EcdhEs.privateKeyFromScalar(BigInteger(case.getString("ephemeralPrivateScalar"), 16))
        val epk = hex(case.getString("ephemeralPublicKey"))
        val ephemeralPublicKey = EcdhEs.decodePoint(epk)
        val info = case.getString("info").toByteArray(Charsets.US_ASCII)
        val expectedSharedSecret = hex(case.getString("expectedSharedSecret"))
        val expectedKek = hex(case.getString("expectedKek"))
        val expectedWrapped = hex(case.getString("expectedWrapped"))
        val expectedMasterKey = hex(case.getString("expectedMasterKey"))

        // Both directions of the agreement must land on the same secret: the device
        // (static private + ephemeral public) and the wrapper (ephemeral private +
        // static public) never share more than their two public keys in the real
        // protocol.
        val fromDevice = KeyAgreement.getInstance("ECDH").apply {
            init(staticPrivateKey)
            doPhase(ephemeralPublicKey, true)
        }.generateSecret()
        val fromWrapper = KeyAgreement.getInstance("ECDH").apply {
            init(ephemeralPrivateKey)
            doPhase(staticPublicKey, true)
        }.generateSecret()
        assertArrayEquals(id, expectedSharedSecret, fromDevice)
        assertArrayEquals(id, expectedSharedSecret, fromWrapper)

        val kek = Hkdf.sha256(expectedSharedSecret, epk, info, 32)
        assertArrayEquals(id, expectedKek, kek)
        assertArrayEquals(id, expectedWrapped, AesKw.wrap(kek, expectedMasterKey))
        assertArrayEquals(id, expectedMasterKey, AesKw.unwrap(kek, expectedWrapped))

        // Exercise EcdhEs.unwrap itself too, not just the underlying primitives.
        assertArrayEquals(id, expectedMasterKey, EcdhEs.unwrap(staticPrivateKey, epk, expectedWrapped))

        // And EcdhEs.wrap, from the wrapper's side — a fresh ephemeral key each call
        // means the ciphertext won't match, so re-derive and unwrap instead of
        // comparing bytes directly.
        val rewrapped = EcdhEs.wrap(staticPublicKey, expectedMasterKey)
        assertArrayEquals(id, expectedMasterKey, EcdhEs.unwrap(staticPrivateKey, rewrapped.epk, rewrapped.wrappedKey))
    }

    private fun runByteRange(case: JSONObject) {
        val id = case.getString("id")
        val cipherLength = case.getLong("cipherLength")
        val ranges = case.getJSONArray("ranges")
        for (i in 0 until ranges.length()) {
            val r = ranges.getJSONObject(i)
            val (start, end, trim) = ByteRangeMapper.cipherRangeFor(
                r.getLong("plainStart"),
                r.getLong("plainEnd"),
                cipherLength,
            )
            assertEquals("$id[$i].cipherStart", r.getLong("cipherStart"), start)
            assertEquals("$id[$i].cipherEnd", r.getLong("cipherEnd"), end)
            assertEquals("$id[$i].trimFront", r.getLong("trimFront"), trim)
        }
    }
}
