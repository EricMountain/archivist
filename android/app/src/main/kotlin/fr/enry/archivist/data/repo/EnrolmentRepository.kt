package fr.enry.archivist.data.repo

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import fr.enry.archivist.crypto.DeviceKeyProvider
import fr.enry.archivist.crypto.DeviceKeystoreUnsupportedException
import fr.enry.archivist.crypto.KeyCustody
import fr.enry.archivist.crypto.NoSecureLockScreenException
import fr.enry.archivist.crypto.RecoveryCode
import fr.enry.archivist.data.local.CachedDeviceWrap
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.KdfParamsDto
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.PostKeyWrapRequest
import fr.enry.archivist.data.remote.PutHashSecretRequest
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** What the app should show right after sign-in, before it can decrypt anything. */
sealed interface EnrolmentStep {
    data object Unlocked : EnrolmentStep

    /** No key wrappings exist for this owner at all — this is the first device. */
    data object NeedsFirstEnrolment : EnrolmentStep

    /** A wrapping exists but this device can't use its own Keystore key yet — either
     * it's a later device, or [reenrolling] because this device's previous wrapping
     * was just found permanently invalidated (a lock-screen change). */
    data class NeedsRecoveryCode(val reenrolling: Boolean) : EnrolmentStep

    /** This device's key is fine, but the Keystore hasn't seen a device-credential/
     * biometric unlock within [DeviceKeystore]'s time-based auth window (confirmed
     * live: this is the *ordinary* case, not just a stale-window edge case — a freshly
     * booted device has never satisfied it at all). Not [Failed]: the fix is "unlock
     * your device", not an error to report. */
    data object NeedsDeviceUnlock : EnrolmentStep

    data object NetworkError : EnrolmentStep

    data class Failed(val message: String) : EnrolmentStep
}

sealed interface RecoveryAttemptResult {
    data object Success : RecoveryAttemptResult

    data object Mistyped : RecoveryAttemptResult

    data object WrongCodeOrCorrupted : RecoveryAttemptResult

    data class DeviceKeystoreUnsupported(val sdkInt: Int) : RecoveryAttemptResult

    data object NetworkError : RecoveryAttemptResult

    data class Failed(val message: String) : RecoveryAttemptResult
}

/**
 * Plan step 2.5: enrols this device's Keystore key against the owner's master key, one
 * of two ways (first device generates everything; a later device recovers via the
 * code), and re-enrols after `KeyPermanentlyInvalidatedException`. See `KeyCustody` in
 * `:core:crypto` for the actual cryptography — this class is purely network + local
 * storage plumbing around it.
 *
 * First-enrolment writes to the server are deliberately deferred until
 * [confirmFirstEnrolment] has verified the user typed the recovery code back
 * correctly ([finishFirstEnrolment] does the writes) — see "Enrolment is not complete
 * until..." in `crypto-format.md`. If the app is killed before confirmation, nothing
 * was ever persisted server-side, so restarting the flow from scratch (a fresh master
 * key, a fresh code) is always safe.
 */
class EnrolmentRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val enrolmentStore: EnrolmentStore,
        private val deviceKeystore: DeviceKeyProvider,
        private val masterKeyHolder: MasterKeyHolder,
        private val hashSecretHolder: HashSecretHolder,
    ) {
        private var pendingFirstEnrolment: KeyCustody.FirstEnrolment? = null
        private var pendingRecoveryWrapId: String? = null
        private var pendingRecoveryRegeneration: KeyCustody.RecoveryRegeneration? = null

        /** Set when a silent unlock hit `KeyPermanentlyInvalidatedException`: the old
         * wrapping is permanently dead and gets retired once a replacement exists —
         * see the ordering note in [attemptRecovery]. */
        private var staleWrapIdToRetire: String? = null

        /** Decides what to show right after sign-in: silently unlock if this device
         * already holds a working wrapping, otherwise figure out whether it's the
         * first device for this owner or needs a recovery code. */
        suspend fun determineStep(): EnrolmentStep {
            val instance = currentInstanceOrThrow()
            val storedWrapId = enrolmentStore.deviceWrapId(instance.host)
            return if (deviceKeystore.exists() && storedWrapId != null) {
                trySilentUnlock(instance, storedWrapId)
            } else {
                determineFromServer(instance)
            }
        }

        private suspend fun trySilentUnlock(
            instance: StoredInstance,
            wrapId: String,
        ): EnrolmentStep {
            val api = apiFor(instance)
            val material =
                try {
                    val wrap = api.getKeys(keysUrl(instance.document.apiBase), wrapId = wrapId).wraps.find { it.wrapId == wrapId }
                    val epk = wrap?.epk
                    val wrappedKey = wrap?.wrappedKey
                    if (epk == null || wrappedKey == null) {
                        return EnrolmentStep.Failed("this device's key wrapping is missing on the server")
                    }
                    // Cache it (not secret — see EnrolmentStore's own doc) so the next
                    // launch can still unlock if this one can't reach the network.
                    enrolmentStore.saveCachedDeviceWrap(instance.host, CachedDeviceWrap(epk, wrappedKey))
                    CachedDeviceWrap(epk, wrappedKey)
                } catch (e: IOException) {
                    // Offline: fall back to whatever this device cached from its last
                    // successful fetch, rather than failing outright — "the app opens
                    // offline showing cached thumbnails" (plan step 2.11) needs this
                    // silent-unlock step to survive being offline too, or nothing past
                    // it (the timeline itself, which *is* fully offline-capable) is
                    // ever reachable. No cache yet (never unlocked before on this
                    // device) still reports NetworkError, same as before this existed.
                    enrolmentStore.cachedDeviceWrap(instance.host) ?: return EnrolmentStep.NetworkError
                }
            val (epk, wrappedKey) = material

            return try {
                // Keystore IPC + an ECDH agreement -- real, blocking work; never on
                // the caller's (likely Main) dispatcher.
                when (
                    val result =
                        withContext(Dispatchers.Default) {
                            KeyCustody.unwrapForDevice(deviceKeystore.privateKey(), decode(epk), decode(wrappedKey))
                        }
                ) {
                    is KeyCustody.DeviceUnwrapResult.Success -> {
                        masterKeyHolder.set(result.masterKey)
                        EnrolmentStep.Unlocked
                    }
                    KeyCustody.DeviceUnwrapResult.InvalidWrap ->
                        EnrolmentStep.Failed("this device's key wrapping doesn't match its Keystore key")
                }
            } catch (e: KeyPermanentlyInvalidatedException) {
                // The lock screen changed. This wrapping can never decrypt again.
                deviceKeystore.delete()
                enrolmentStore.clearDeviceWrapId(instance.host)
                enrolmentStore.clearCachedDeviceWrap(instance.host)
                staleWrapIdToRetire = wrapId
                determineFromServer(instance, reenrolling = true)
            } catch (e: UserNotAuthenticatedException) {
                // Sibling of KeyPermanentlyInvalidatedException, not a subclass --
                // confirmed live on an emulator: this is what a device-key-gated
                // Keystore operation throws when DeviceKeystore's time-based auth
                // window (see its own doc) hasn't been satisfied recently. The key
                // itself is fine; nothing to retire.
                EnrolmentStep.NeedsDeviceUnlock
            }
        }

        private suspend fun determineFromServer(
            instance: StoredInstance,
            reenrolling: Boolean = false,
        ): EnrolmentStep {
            val api = apiFor(instance)
            val wraps =
                try {
                    api.getKeys(keysUrl(instance.document.apiBase)).wraps
                } catch (e: IOException) {
                    return EnrolmentStep.NetworkError
                }
            if (wraps.isEmpty()) return EnrolmentStep.NeedsFirstEnrolment

            val recoveryWrapId =
                wraps.find { it.kind == "recovery" }?.wrapId
                    ?: return EnrolmentStep.Failed("no recovery wrapping exists for this library")
            pendingRecoveryWrapId = recoveryWrapId
            return EnrolmentStep.NeedsRecoveryCode(reenrolling)
        }

        /** Generates a fresh master key and every wrapping for it, entirely in
         * memory — see the class doc for why nothing is sent to the server yet.
         * Keystore keygen + Argon2id are real blocking work, hence `suspend` and
         * `Dispatchers.Default` rather than a plain synchronous call. */
        suspend fun beginFirstEnrolment(): Result<KeyCustody.FirstEnrolment> =
            withContext(Dispatchers.Default) {
                try {
                    val devicePublicKey = deviceKeystore.ensureKeyPair()
                    val enrolment = KeyCustody.enrolFirstDevice(devicePublicKey)
                    pendingFirstEnrolment = enrolment
                    Result.success(enrolment)
                } catch (e: DeviceKeystoreUnsupportedException) {
                    Result.failure(e)
                } catch (e: NoSecureLockScreenException) {
                    Result.failure(e)
                }
            }

        /** Cheap, instant, no network: does [typed] match the code [beginFirstEnrolment]
         * generated? Call [finishFirstEnrolment] only once this returns `true`. */
        fun confirmFirstEnrolment(typed: String): Boolean {
            val pending = pendingFirstEnrolment ?: return false
            return KeyCustody.confirmsGeneratedCode(typed, pending.recoveryCode)
        }

        /** Persists everything [beginFirstEnrolment] generated: allocates `mk-1`,
         * POSTs the device and recovery wrappings, and PUTs the wrapped `hashSecret`. */
        suspend fun finishFirstEnrolment(): Result<Unit> {
            val pending =
                pendingFirstEnrolment ?: return Result.failure(IllegalStateException("no pending enrolment to finish"))
            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            val apiBase = instance.document.apiBase

            return try {
                val version = api.postKeyVersion(keysVersionUrl(apiBase))

                val deviceWrapId =
                    api.postKey(
                        keysUrl(apiBase),
                        PostKeyWrapRequest(
                            kind = "device",
                            label = deviceLabel(),
                            wrapAlg = "ECDH-ES+AES-KW",
                            wrappedKey = encode(pending.deviceWrap.wrappedKey),
                            epk = encode(pending.deviceWrap.epk),
                        ),
                    ).wrapId

                api.postKey(
                    keysUrl(apiBase),
                    PostKeyWrapRequest(
                        kind = "recovery",
                        label = "Recovery code",
                        wrapAlg = "AES-KW",
                        wrappedKey = encode(pending.recoveryWrap.wrappedKey),
                        kdfSalt = encode(pending.recoveryWrap.kdfSalt),
                        kdfParams =
                            KdfParamsDto(
                                alg = "argon2id",
                                m = KeyCustody.formatMemoryKib(pending.recoveryWrap.memoryKib),
                                t = pending.recoveryWrap.iterations,
                                p = pending.recoveryWrap.parallelism,
                            ),
                    ),
                )

                val hashSecretResponse =
                    api.putHashSecret(
                        hashSecretUrl(apiBase),
                        PutHashSecretRequest(
                            encHashSecret = encode(pending.encHashSecret),
                            hashSecretKeyId = version.masterKeyVer,
                        ),
                    )
                // Response<T>, not a bare suspend return type -- see ArchivistApi's
                // doc on why -- so a non-2xx here doesn't throw on its own.
                if (!hashSecretResponse.isSuccessful) {
                    return Result.failure(HttpException(hashSecretResponse))
                }

                enrolmentStore.saveDeviceWrapId(instance.host, deviceWrapId)
                masterKeyHolder.set(pending.masterKey)
                // This device generated the hash secret itself — no need to round-trip
                // through GET /keys/hash-secret just to get back what it already has.
                hashSecretHolder.set(pending.hashSecret)
                pendingFirstEnrolment = null
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        /** The later-device / re-enrolment path: verify [typed] against the recovery
         * wrapping [determineStep] found, then enrol this device's Keystore key
         * against the recovered master key. */
        suspend fun attemptRecovery(typed: String): RecoveryAttemptResult {
            val wrapId =
                pendingRecoveryWrapId ?: return RecoveryAttemptResult.Failed("no recovery wrapping to try")
            // Fail fast on a typo -- no network, no Argon2id. See "Verification" in
            // crypto-format.md.
            if (RecoveryCode.verify(typed) == null) return RecoveryAttemptResult.Mistyped

            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            val apiBase = instance.document.apiBase

            val wrap =
                try {
                    api.getKeys(keysUrl(apiBase), wrapId = wrapId).wraps.find { it.wrapId == wrapId }
                } catch (e: IOException) {
                    return RecoveryAttemptResult.NetworkError
                }
            val kdfSalt = wrap?.kdfSalt
            val kdfParams = wrap?.kdfParams
            val wrappedKey = wrap?.wrappedKey
            if (kdfSalt == null || kdfParams == null || wrappedKey == null) {
                return RecoveryAttemptResult.Failed("recovery wrapping is missing required fields")
            }

            // Argon2id (deliberately slow) plus Keystore keygen and an ECDH agreement
            // -- real, blocking work; never on the caller's (likely Main) dispatcher.
            val masterKey =
                when (
                    val result =
                        withContext(Dispatchers.Default) {
                            KeyCustody.unwrapWithRecoveryCode(
                                typed,
                                decode(kdfSalt),
                                KeyCustody.parseMemoryKib(kdfParams.m),
                                kdfParams.t,
                                kdfParams.p,
                                decode(wrappedKey),
                            )
                        }
                ) {
                    is KeyCustody.RecoveryUnwrapResult.Success -> result.masterKey
                    KeyCustody.RecoveryUnwrapResult.Mistyped -> return RecoveryAttemptResult.Mistyped
                    KeyCustody.RecoveryUnwrapResult.WrongCodeOrCorrupted ->
                        return RecoveryAttemptResult.WrongCodeOrCorrupted
                }

            val deviceWrap =
                try {
                    withContext(Dispatchers.Default) {
                        val devicePublicKey = deviceKeystore.ensureKeyPair()
                        masterKey.wrapForDevice(devicePublicKey)
                    }
                } catch (e: DeviceKeystoreUnsupportedException) {
                    return RecoveryAttemptResult.DeviceKeystoreUnsupported(e.sdkInt)
                } catch (e: NoSecureLockScreenException) {
                    return RecoveryAttemptResult.Failed(e.message ?: "no secure lock screen is set on this device")
                }

            return try {
                val newWrapId =
                    api.postKey(
                        keysUrl(apiBase),
                        PostKeyWrapRequest(
                            kind = "device",
                            label = deviceLabel(),
                            wrapAlg = "ECDH-ES+AES-KW",
                            wrappedKey = encode(deviceWrap.wrappedKey),
                            epk = encode(deviceWrap.epk),
                        ),
                    ).wrapId
                enrolmentStore.saveDeviceWrapId(instance.host, newWrapId)
                masterKeyHolder.set(masterKey)
                pendingRecoveryWrapId = null

                // The new wrap must exist server-side *before* the old one is deleted:
                // deleteKeyWrap (repo/keys.ts) refuses to drop below two remaining
                // wrappings, and a single-device library only has two (device +
                // recovery) before this line. Best-effort — the old Keystore key is
                // gone either way, so a failure here is leftover hygiene, not data loss.
                staleWrapIdToRetire?.let { stale ->
                    runCatching { api.deleteKey(keyUrl(apiBase, stale)) }
                    staleWrapIdToRetire = null
                }
                RecoveryAttemptResult.Success
            } catch (e: IOException) {
                RecoveryAttemptResult.NetworkError
            } catch (e: HttpException) {
                RecoveryAttemptResult.Failed(e.message() ?: "request failed")
            }
        }

        /** Lazy, cached fetch of the owner's hash secret — needed for `contentHash`
         * (plan step 2.7's scanner, eventually 2.10's upload worker), not for
         * unlocking itself, which is why this isn't called from [trySilentUnlock] or
         * [attemptRecovery] directly: no point coupling the unlock path to a second
         * network round trip for something that's only needed later, and maybe not
         * every session (the app might never scan before it's backgrounded).
         * Requires [masterKeyHolder] to already be set — call after [determineStep]
         * (or [finishFirstEnrolment]/[attemptRecovery]) reaches [EnrolmentStep.Unlocked]. */
        suspend fun ensureHashSecret(): Result<ByteArray> {
            hashSecretHolder.current.value?.let { return Result.success(it) }
            val masterKey =
                masterKeyHolder.current.value
                    ?: return Result.failure(IllegalStateException("no master key — device must be unlocked first"))

            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            return try {
                val response = api.getHashSecret(hashSecretUrl(instance.document.apiBase))
                if (!response.isSuccessful) {
                    return Result.failure(HttpException(response))
                }
                val body =
                    response.body() ?: return Result.failure(IllegalStateException("empty hash-secret response body"))
                // Plain AES-KW against an in-memory master key — unlike the Keystore/
                // ECDH operations elsewhere in this file, nothing here touches hardware
                // or blocks, so no Dispatchers.Default hop is needed.
                val hashSecret = masterKey.unwrapHashSecret(decode(body.encHashSecret))
                hashSecretHolder.set(hashSecret)
                Result.success(hashSecret)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        /** Plan step 2.14's "Keys" settings section: every wrapping this owner has —
         * metadata only (no `wrapId` query param means the server never returns this
         * device's own unwrapping material either, unlike [trySilentUnlock]'s call). */
        suspend fun listKeys(): Result<List<KeyWrapDto>> {
            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            return try {
                Result.success(api.getKeys(keysUrl(instance.document.apiBase)).wraps)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        /** De-enrolling a device/passkey, or discarding a stale recovery wrapping —
         * `deleteKeyWrap` server-side enforces "at least two wrappings, one a
         * recovery" (design.md), so this can fail with a 409 the caller should show
         * verbatim rather than retry. */
        suspend fun removeKey(wrapId: String): Result<Unit> {
            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            return try {
                val response = api.deleteKey(keyUrl(instance.document.apiBase, wrapId))
                if (!response.isSuccessful) return Result.failure(HttpException(response))
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        /** Step 1 of "regenerate recovery code" (Settings > Keys): a fresh code
         * against the master key this device already holds unlocked — see
         * [KeyCustody.regenerateRecoveryWrap]'s doc for why nothing is sent to the
         * server until [confirmRecoveryRegeneration] verifies the user copied it down
         * correctly, same "confirm before you commit" shape as first enrolment. */
        fun beginRecoveryRegeneration(): Result<KeyCustody.RecoveryRegeneration> {
            val masterKey =
                masterKeyHolder.current.value
                    ?: return Result.failure(IllegalStateException("no master key — device must be unlocked first"))
            val regeneration = KeyCustody.regenerateRecoveryWrap(masterKey)
            pendingRecoveryRegeneration = regeneration
            return Result.success(regeneration)
        }

        /** Cheap, instant, no network — same shape as [confirmFirstEnrolment]. */
        fun confirmRecoveryRegeneration(typed: String): Boolean {
            val pending = pendingRecoveryRegeneration ?: return false
            return KeyCustody.confirmsGeneratedCode(typed, pending.recoveryCode)
        }

        /** POSTs the new recovery wrapping, then deletes the old one — in that order,
         * so the invariant of "at least one recovery wrapping at all times" (design.md)
         * is never violated even for the instant between the two calls. Best-effort on
         * the delete: if it fails, the owner ends up with two recovery codes rather
         * than one — surprising but harmless (either still unwraps the same master
         * key), and worth surfacing rather than silently swallowing. */
        suspend fun finishRecoveryRegeneration(): Result<Unit> {
            val pending =
                pendingRecoveryRegeneration
                    ?: return Result.failure(IllegalStateException("no pending recovery-code regeneration to finish"))
            val instance = currentInstanceOrThrow()
            val api = apiFor(instance)
            val apiBase = instance.document.apiBase

            return try {
                val oldRecoveryWrapId = api.getKeys(keysUrl(apiBase)).wraps.find { it.kind == "recovery" }?.wrapId

                api.postKey(
                    keysUrl(apiBase),
                    PostKeyWrapRequest(
                        kind = "recovery",
                        label = "Recovery code",
                        wrapAlg = "AES-KW",
                        wrappedKey = encode(pending.recoveryWrap.wrappedKey),
                        kdfSalt = encode(pending.recoveryWrap.kdfSalt),
                        kdfParams =
                            KdfParamsDto(
                                alg = "argon2id",
                                m = KeyCustody.formatMemoryKib(pending.recoveryWrap.memoryKib),
                                t = pending.recoveryWrap.iterations,
                                p = pending.recoveryWrap.parallelism,
                            ),
                    ),
                )
                oldRecoveryWrapId?.let { runCatching { api.deleteKey(keyUrl(apiBase, it)) } }
                pendingRecoveryRegeneration = null
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        private fun apiFor(instance: StoredInstance): ArchivistApi =
            archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)

        private suspend fun currentInstanceOrThrow(): StoredInstance =
            instanceStore.current.first() ?: error("no connected instance — 2.3/2.4 must complete before 2.5 can run")

        private fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
    }

private fun keysUrl(apiBase: String) = "$apiBase/keys"

private fun keyUrl(
    apiBase: String,
    wrapId: String,
) = "$apiBase/keys/$wrapId"

private fun keysVersionUrl(apiBase: String) = "$apiBase/keys/version"

private fun hashSecretUrl(apiBase: String) = "$apiBase/keys/hash-secret"
