package fr.enry.archivist.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * What [EnrolmentRepository][fr.enry.archivist.data.repo.EnrolmentRepository] actually
 * needs from a device key — [DeviceKeystore] is the only real implementation, but the
 * interface exists so the repository's own sequencing/error-handling logic (which is
 * most of its value) can be unit tested against a plain in-memory fake instead of a
 * real `AndroidKeyStore`, which no JVM unit test environment provides.
 */
interface DeviceKeyProvider {
    fun exists(): Boolean

    fun publicKey(): PublicKey?

    fun privateKey(): PrivateKey

    /** Generates the keypair if absent, returning the public half either way. */
    fun ensureKeyPair(): PublicKey

    fun delete()
}

/**
 * The Android-Keystore-resident EC-P256 keypair behind `kind: device` wrapping — plan
 * step 2.5, "Key unlock" in android.md, "Master key wrapping" in crypto-format.md.
 * `setUserAuthenticationRequired(true)` so every use sits behind the lock screen.
 *
 * Not unit tested here: the `"AndroidKeyStore"` JCA provider only exists on a real
 * device, the same gap [fr.enry.archivist.crypto]'s `EcdhEs` doc already calls out and
 * `PasskeyCeremony` (`:app`) documents for Credential Manager.
 *
 * **`PURPOSE_AGREE_KEY` — required to use a Keystore-resident EC key with
 * `KeyAgreement` at all — was added in API 31 (Android 12).** This was not caught by
 * plan step 2.4a, whose real test device (a Motorola Edge 20 Lite) happens to run
 * Android 13; it surfaced only while wiring this step, from the platform's own
 * `ApiSince=31` annotation on the constant. `minSdk` is 28, and RSA-OAEP-256 decrypt
 * was already ruled out on Keystore-resident keys by 2.4a — so **as things stand there
 * is no working hardware-backed device-wrap route on API 28–30 at all.** [ensureKeyPair]
 * fails fast with [DeviceKeystoreUnsupportedException] on those API levels rather than
 * letting the platform throw a confusing `IllegalArgumentException` out of
 * `KeyGenParameterSpec.Builder`. See open question 3 in `design.md` — this needs a
 * product decision (raise `minSdk` to 31, or design a software-backed fallback route),
 * not a silent workaround here.
 */
class DeviceKeystore : DeviceKeyProvider {
    private val alias: String = ALIAS

    override fun exists(): Boolean = keyStore().containsAlias(alias)

    override fun publicKey(): PublicKey? = keyStore().getCertificate(alias)?.publicKey

    override fun privateKey(): PrivateKey =
        keyStore().getKey(alias, null) as? PrivateKey
            ?: error("device key '$alias' does not exist -- call ensureKeyPair() first")

    override fun ensureKeyPair(): PublicKey {
        publicKey()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw DeviceKeystoreUnsupportedException(Build.VERSION.SDK_INT)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setUserAuthenticationRequired(true)
                // TIME-BASED, not auth-per-use -- confirmed live on an emulator (API
                // 37) that auth-per-use (a 0 timeout) is unusable here: it requires
                // the operation to be driven through a `BiometricPrompt.CryptoObject`
                // ceremony, and the `KeyAgreement` overload of that constructor exists
                // only in androidx.biometric 1.4.0-alpha06+ -- there is no stable
                // release with it (stable is still 1.1.0, from 2021), so shipping
                // auth-per-use here would mean depending on an alpha library
                // indefinitely. A positive timeout uses the platform's other
                // documented mode instead (see `BiometricPrompt.CryptoObject`'s own
                // class doc): the key is simply usable for `AUTH_VALIDITY_SECONDS`
                // after the user last unlocked the device with one of the given
                // authenticators -- no CryptoObject, no prompt, no alpha dependency.
                // Plain setUserAuthenticationRequired(true) with no explicit
                // setUserAuthenticationParameters also separately confirmed live to
                // default to requiring a *biometric* specifically -- keygen throws
                // InvalidAlgorithmParameterException ("At least one biometric must be
                // enrolled...") on a device that only has a PIN/pattern/password set,
                // an entirely ordinary device state. AUTH_DEVICE_CREDENTIAL is ORed in
                // so a lock-screen PIN alone is sufficient either way.
                .setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                .build()
        try {
            generator.initialize(spec)
        } catch (e: InvalidAlgorithmParameterException) {
            // Confirmed live: a device with no PIN/pattern/password set at all throws
            // this (wrapping IllegalStateException: "Secure lock screen must be enabled
            // to create keys requiring user authentication") the moment a fresh
            // emulator or device tries to enrol, since setUserAuthenticationRequired(true)
            // has nothing to bind to yet. A real, ordinary failure mode -- not a crash.
            if (e.cause is IllegalStateException) throw NoSecureLockScreenException(e)
            throw e
        }
        return generator.generateKeyPair().public
    }

    /** For re-enrolment after `KeyPermanentlyInvalidatedException` (a lock-screen
     * change): the existing entry can never decrypt again, so it's removed rather than
     * left as permanent dead weight the app would otherwise keep tripping over. */
    override fun delete() {
        if (exists()) keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val CURVE = "secp256r1"
        const val ALIAS = "archivist-device-key"

        /** How long after the user unlocks their device this key stays usable, per
         * `setUserAuthenticationParameters`'s time-based mode. Tunable; not derived
         * from any spec. 5 minutes balances "the app doesn't nag" against "an
         * unattended unlocked phone doesn't hold this open forever". */
        const val AUTH_VALIDITY_SECONDS = 300
    }
}

/** Thrown by [DeviceKeystore.ensureKeyPair] below API 31, where `PURPOSE_AGREE_KEY`
 * does not exist on the platform at all — see the class doc above. */
class DeviceKeystoreUnsupportedException(val sdkInt: Int) :
    Exception("device key agreement needs Android 12 (API 31) or later; this device is API $sdkInt")

/** Thrown by [DeviceKeystore.ensureKeyPair] when the device has no secure lock screen
 * (PIN, pattern, or password) set up at all — see the call site above. */
class NoSecureLockScreenException(cause: Throwable) :
    Exception("set a PIN, pattern or password on this device, then try again", cause)
