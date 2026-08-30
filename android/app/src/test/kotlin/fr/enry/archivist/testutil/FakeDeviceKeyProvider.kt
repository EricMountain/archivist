package fr.enry.archivist.testutil

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import fr.enry.archivist.crypto.DeviceKeyProvider
import fr.enry.archivist.crypto.DeviceKeystoreUnsupportedException
import fr.enry.archivist.crypto.NoSecureLockScreenException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Stands in for [fr.enry.archivist.crypto.DeviceKeystore] — no JVM unit test
 * environment has a real `"AndroidKeyStore"` provider, so this uses a plain EC keypair
 * instead. Good enough for [fr.enry.archivist.data.repo.EnrolmentRepository]'s own
 * sequencing/error-handling logic, which is all any of these tests are exercising —
 * see [DeviceKeyProvider]'s class doc for why the interface exists at all.
 */
class FakeDeviceKeyProvider : DeviceKeyProvider {
    private var keyPair: java.security.KeyPair? = null

    /** When set, [ensureKeyPair] throws it instead of generating — simulates API < 31. */
    var unsupportedException: DeviceKeystoreUnsupportedException? = null

    /** When set, [ensureKeyPair] throws it instead of generating — simulates a device
     * with no PIN/pattern/password set at all. */
    var noSecureLockScreenException: NoSecureLockScreenException? = null

    /** When set, the *next* call to [privateKey] throws it once and clears itself —
     * simulates a lock-screen change invalidating the key mid-use. */
    var invalidateOnNextPrivateKeyUse: KeyPermanentlyInvalidatedException? = null

    /** When set, the *next* call to [privateKey] throws it once and clears itself —
     * simulates [DeviceKeystore][fr.enry.archivist.crypto.DeviceKeystore]'s time-based
     * auth window not having been satisfied recently (confirmed live on an emulator:
     * the ordinary case, not just an edge case — see that class's doc). */
    var requireAuthenticationOnNextPrivateKeyUse: UserNotAuthenticatedException? = null

    override fun exists(): Boolean = keyPair != null

    override fun publicKey(): PublicKey? = keyPair?.public

    override fun privateKey(): PrivateKey {
        invalidateOnNextPrivateKeyUse?.let {
            invalidateOnNextPrivateKeyUse = null
            throw it
        }
        requireAuthenticationOnNextPrivateKeyUse?.let {
            requireAuthenticationOnNextPrivateKeyUse = null
            throw it
        }
        return keyPair?.private ?: error("no key generated -- call ensureKeyPair() first")
    }

    override fun ensureKeyPair(): PublicKey {
        keyPair?.let { return it.public }
        unsupportedException?.let { throw it }
        noSecureLockScreenException?.let { throw it }
        val generated =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keyPair = generated
        return generated.public
    }

    override fun delete() {
        keyPair = null
    }
}
