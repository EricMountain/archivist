package fr.enry.archivist.crypto

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything here is plain-JVM testable because [KeyCustody] and [MasterKey] never
 * touch `AndroidKeyStore` directly -- they take `PublicKey`/`PrivateKey` from whoever
 * calls them. [DeviceKeystore] itself has no equivalent test; see its class doc.
 */
class KeyCustodyTest {
    private fun freshEcKeyPair() =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** [MasterKey] never exposes its raw bytes, so two [MasterKey] instances are
     * compared by wrapping the same DEK under both -- AES-KW is deterministic, so this
     * matches only if the underlying keys are identical. */
    private fun assertSameKey(
        a: MasterKey,
        b: MasterKey,
    ) {
        val dek = EnvelopeCrypto.generateDek()
        assertArrayEquals(a.wrapDek(dek), b.wrapDek(dek))
    }

    @Test
    fun `first-device enrolment round-trips through the device wrapping`() {
        val device = freshEcKeyPair()
        val enrolment = KeyCustody.enrolFirstDevice(device.public)

        val result = KeyCustody.unwrapForDevice(device.private, enrolment.deviceWrap.epk, enrolment.deviceWrap.wrappedKey)

        assertTrue(result is KeyCustody.DeviceUnwrapResult.Success)
        assertSameKey(enrolment.masterKey, (result as KeyCustody.DeviceUnwrapResult.Success).masterKey)
    }

    @Test
    fun `first-device enrolment round-trips through the recovery wrapping`() {
        val device = freshEcKeyPair()
        val enrolment = KeyCustody.enrolFirstDevice(device.public)

        val result =
            KeyCustody.unwrapWithRecoveryCode(
                rawCode = enrolment.recoveryCode.code,
                kdfSalt = enrolment.recoveryWrap.kdfSalt,
                memoryKib = enrolment.recoveryWrap.memoryKib,
                iterations = enrolment.recoveryWrap.iterations,
                parallelism = enrolment.recoveryWrap.parallelism,
                wrappedKey = enrolment.recoveryWrap.wrappedKey,
            )

        assertTrue(result is KeyCustody.RecoveryUnwrapResult.Success)
        assertSameKey(enrolment.masterKey, (result as KeyCustody.RecoveryUnwrapResult.Success).masterKey)
    }

    @Test
    fun `a later device enrols against a master key recovered via the code, with no raw bytes ever leaving MasterKey`() {
        val firstDevice = freshEcKeyPair()
        val enrolment = KeyCustody.enrolFirstDevice(firstDevice.public)

        val recovered =
            KeyCustody.unwrapWithRecoveryCode(
                enrolment.recoveryCode.code,
                enrolment.recoveryWrap.kdfSalt,
                enrolment.recoveryWrap.memoryKib,
                enrolment.recoveryWrap.iterations,
                enrolment.recoveryWrap.parallelism,
                enrolment.recoveryWrap.wrappedKey,
            ) as KeyCustody.RecoveryUnwrapResult.Success
        assertSameKey(enrolment.masterKey, recovered.masterKey)

        // The second device enrols directly off the recovered MasterKey -- this is
        // exactly the operation that used to be impossible when wrapForDevice took raw
        // bytes instead of living on MasterKey itself.
        val secondDevice = freshEcKeyPair()
        val newDeviceWrap = recovered.masterKey.wrapForDevice(secondDevice.public)
        val unwrapped = KeyCustody.unwrapForDevice(secondDevice.private, newDeviceWrap.epk, newDeviceWrap.wrappedKey)

        assertTrue(unwrapped is KeyCustody.DeviceUnwrapResult.Success)
        assertSameKey(enrolment.masterKey, (unwrapped as KeyCustody.DeviceUnwrapResult.Success).masterKey)
    }

    @Test
    fun `a mistyped recovery code is rejected before Argon2id runs`() {
        val device = freshEcKeyPair()
        val enrolment = KeyCustody.enrolFirstDevice(device.public)
        val original = enrolment.recoveryCode.code
        val mistyped = otherChar(original[0]) + original.substring(1)

        val result =
            KeyCustody.unwrapWithRecoveryCode(
                mistyped,
                enrolment.recoveryWrap.kdfSalt,
                enrolment.recoveryWrap.memoryKib,
                enrolment.recoveryWrap.iterations,
                enrolment.recoveryWrap.parallelism,
                enrolment.recoveryWrap.wrappedKey,
            )

        assertEquals(KeyCustody.RecoveryUnwrapResult.Mistyped, result)
    }

    @Test
    fun `a wrong but validly-checksummed code fails distinctly from a mistyped one`() {
        val device = freshEcKeyPair()
        val enrolment = KeyCustody.enrolFirstDevice(device.public)
        val otherCode = RecoveryCode.generate().code

        val result =
            KeyCustody.unwrapWithRecoveryCode(
                otherCode,
                enrolment.recoveryWrap.kdfSalt,
                enrolment.recoveryWrap.memoryKib,
                enrolment.recoveryWrap.iterations,
                enrolment.recoveryWrap.parallelism,
                enrolment.recoveryWrap.wrappedKey,
            )

        assertEquals(KeyCustody.RecoveryUnwrapResult.WrongCodeOrCorrupted, result)
    }

    @Test
    fun `confirmsGeneratedCode accepts the exact code and rejects a mistyped one`() {
        val generated = RecoveryCode.generate()
        assertTrue(KeyCustody.confirmsGeneratedCode(generated.code, generated))
        assertFalse(KeyCustody.confirmsGeneratedCode(RecoveryCode.generate().code, generated))
    }

    @Test
    fun `memory KiB round-trips through the human-readable wire format`() {
        assertEquals(65536, KeyCustody.parseMemoryKib("64MiB"))
        assertEquals("64MiB", KeyCustody.formatMemoryKib(65536))
        assertEquals(1024, KeyCustody.parseMemoryKib("1MiB"))
        assertEquals(2 * 1024 * 1024, KeyCustody.parseMemoryKib("2GiB"))
        assertEquals(512, KeyCustody.parseMemoryKib("512KiB"))
    }

    private fun otherChar(c: Char): Char = RecoveryCode.ALPHABET.first { it != c }
}
