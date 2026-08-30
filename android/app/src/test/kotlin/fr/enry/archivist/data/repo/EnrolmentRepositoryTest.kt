package fr.enry.archivist.data.repo

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.EcdhEs
import fr.enry.archivist.crypto.KeyCustody
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.NoSecureLockScreenException
import fr.enry.archivist.crypto.RecoveryCode
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.KdfParamsDto
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.KeysResponse
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeDeviceKeyProvider
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EnrolmentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var enrolmentStore: EnrolmentStore
    private lateinit var deviceKeyProvider: FakeDeviceKeyProvider
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var hashSecretHolder: HashSecretHolder
    private lateinit var repository: EnrolmentRepository
    private val json = Json { ignoreUnknownKeys = true }

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("enrolment-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        enrolmentStore = EnrolmentStore(FakeSharedPreferences())
        deviceKeyProvider = FakeDeviceKeyProvider()
        masterKeyHolder = MasterKeyHolder()
        hashSecretHolder = HashSecretHolder()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository =
            EnrolmentRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                enrolmentStore = enrolmentStore,
                deviceKeystore = deviceKeyProvider,
                masterKeyHolder = masterKeyHolder,
                hashSecretHolder = hashSecretHolder,
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private suspend fun connectInstance() {
        instanceStore.save(
            host,
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun freshEcKeyPair() =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun recoveryWrapDto(
        wrapId: String,
        recoveryWrap: KeyCustody.RecoveryWrap,
    ) = KeyWrapDto(
        wrapId = wrapId,
        kind = "recovery",
        label = "Recovery code",
        masterKeyVer = "mk-1",
        wrapAlg = "AES-KW",
        wrappedKey = encode(recoveryWrap.wrappedKey),
        kdfSalt = encode(recoveryWrap.kdfSalt),
        kdfParams = KdfParamsDto("argon2id", KeyCustody.formatMemoryKib(recoveryWrap.memoryKib), recoveryWrap.iterations, recoveryWrap.parallelism),
    )

    private fun deviceWrapDto(
        wrapId: String,
        deviceWrap: EcdhEs.Wrapped,
    ) = KeyWrapDto(
        wrapId = wrapId,
        kind = "device",
        label = "Test device",
        masterKeyVer = "mk-1",
        wrapAlg = "ECDH-ES+AES-KW",
        wrappedKey = encode(deviceWrap.wrappedKey),
        epk = encode(deviceWrap.epk),
    )

    // ------------------------------------------------------------------
    // First-device enrolment
    // ------------------------------------------------------------------

    @Test
    fun `an owner with no key wraps needs first enrolment`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setBody(json.encodeToString(KeysResponse.serializer(), KeysResponse(emptyList()))))

            val step = repository.determineStep()

            assertEquals(EnrolmentStep.NeedsFirstEnrolment, step)
        }

    @Test
    fun `first enrolment generates a code but writes nothing until confirmed`() =
        runTest {
            val enrolment = repository.beginFirstEnrolment().getOrThrow()

            assertEquals(0, server.requestCount)
            assertNull(masterKeyHolder.current.value)
            assertNotNull(enrolment.recoveryCode.code)
        }

    @Test
    fun `first enrolment on a device with no secure lock screen fails cleanly, not a crash`() =
        runTest {
            deviceKeyProvider.noSecureLockScreenException = NoSecureLockScreenException(IllegalStateException())

            val result = repository.beginFirstEnrolment()

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is NoSecureLockScreenException)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `confirming the wrong code fails and leaves enrolment pending`() =
        runTest {
            repository.beginFirstEnrolment().getOrThrow()

            val confirmed = repository.confirmFirstEnrolment("0000000000000000000000000")

            assertEquals(false, confirmed)
        }

    @Test
    fun `confirming the right code and finishing writes every server-side artifact`() =
        runTest {
            connectInstance()
            val enrolment = repository.beginFirstEnrolment().getOrThrow()
            assertTrue(repository.confirmFirstEnrolment(enrolment.recoveryCode.code))

            server.enqueue(MockResponse().setBody("""{"masterKeyVer":"mk-1","rotatedAt":"2026-01-01T00:00:00.000Z"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-device","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-recovery","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repository.finishFirstEnrolment()

            assertTrue(result.isSuccess)
            assertEquals(4, server.requestCount)
            assertEquals("/api/keys/version", server.takeRequest().path)
            val deviceRequest = server.takeRequest()
            assertEquals("/api/keys", deviceRequest.path)
            assertTrue(deviceRequest.body.readUtf8().contains("\"kind\":\"device\""))
            val recoveryRequest = server.takeRequest()
            assertTrue(recoveryRequest.body.readUtf8().contains("\"kind\":\"recovery\""))
            assertEquals("/api/keys/hash-secret", server.takeRequest().path)

            assertEquals("w-device", enrolmentStore.deviceWrapId(host))
            assertNotNull(masterKeyHolder.current.value)
            // The device generated this itself, so it's cached straight from
            // `beginFirstEnrolment`'s result — no separate GET was needed (only 4
            // requests total, asserted above, none of them a hash-secret fetch).
            assertArrayEquals(enrolment.hashSecret, hashSecretHolder.current.value)
        }

    // ------------------------------------------------------------------
    // Later-device recovery
    // ------------------------------------------------------------------

    @Test
    fun `a non-empty wrap list means a recovery code is needed`() =
        runTest {
            connectInstance()
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )

            val step = repository.determineStep()

            assertEquals(EnrolmentStep.NeedsRecoveryCode(reenrolling = false), step)
        }

    @Test
    fun `a mistyped recovery code is rejected without any network call`() =
        runTest {
            connectInstance()
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )
            repository.determineStep()
            assertEquals(1, server.requestCount)

            val result = repository.attemptRecovery("0000000000000000000000000")

            assertEquals(RecoveryAttemptResult.Mistyped, result)
            assertEquals(1, server.requestCount) // no further request
        }

    @Test
    fun `the right recovery code recovers the master key and enrols this device`() =
        runTest {
            connectInstance()
            val sourceEnrolment = KeyCustody.enrolFirstDevice(freshEcKeyPair().public)

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )
            assertEquals(EnrolmentStep.NeedsRecoveryCode(false), repository.determineStep())

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(recoveryWrapDto("rec1", sourceEnrolment.recoveryWrap))),
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-device-2","masterKeyVer":"mk-1"}"""))

            val result = repository.attemptRecovery(sourceEnrolment.recoveryCode.code)

            assertEquals(RecoveryAttemptResult.Success, result)
            assertEquals("w-device-2", enrolmentStore.deviceWrapId(host))
            assertNotNull(masterKeyHolder.current.value)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `recovering on a device with no secure lock screen fails cleanly, not a crash`() =
        runTest {
            connectInstance()
            val sourceEnrolment = KeyCustody.enrolFirstDevice(freshEcKeyPair().public)
            deviceKeyProvider.noSecureLockScreenException = NoSecureLockScreenException(IllegalStateException())

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )
            repository.determineStep()
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(recoveryWrapDto("rec1", sourceEnrolment.recoveryWrap))),
                    ),
                ),
            )

            val result = repository.attemptRecovery(sourceEnrolment.recoveryCode.code)

            assertTrue(result is RecoveryAttemptResult.Failed)
            assertNull(enrolmentStore.deviceWrapId(host)) // never got as far as saving a new wrap
            assertEquals(2, server.requestCount) // no POST /keys attempted
        }

    @Test
    fun `a wrong but validly-checksummed code is distinguished from a typo`() =
        runTest {
            connectInstance()
            val sourceEnrolment = KeyCustody.enrolFirstDevice(freshEcKeyPair().public)
            val wrongCode = RecoveryCode.generate().code

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )
            repository.determineStep()
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(recoveryWrapDto("rec1", sourceEnrolment.recoveryWrap))),
                    ),
                ),
            )

            val result = repository.attemptRecovery(wrongCode)

            assertEquals(RecoveryAttemptResult.WrongCodeOrCorrupted, result)
        }

    // ------------------------------------------------------------------
    // Silent unlock and lock-screen invalidation
    // ------------------------------------------------------------------

    @Test
    fun `a device with a working local key unlocks silently on restart`() =
        runTest {
            connectInstance()
            val devicePublicKey = deviceKeyProvider.ensureKeyPair()
            val enrolment = KeyCustody.enrolFirstDevice(devicePublicKey)
            enrolmentStore.saveDeviceWrapId(host, "w1")

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(deviceWrapDto("w1", enrolment.deviceWrap))),
                    ),
                ),
            )

            val step = repository.determineStep()

            assertEquals(EnrolmentStep.Unlocked, step)
            assertNotNull(masterKeyHolder.current.value)
        }

    @Test
    fun `an unsatisfied time-based auth window asks to unlock, not crash`() =
        runTest {
            connectInstance()
            val devicePublicKey = deviceKeyProvider.ensureKeyPair()
            val enrolment = KeyCustody.enrolFirstDevice(devicePublicKey)
            enrolmentStore.saveDeviceWrapId(host, "w1")
            deviceKeyProvider.requireAuthenticationOnNextPrivateKeyUse = UserNotAuthenticatedException()

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(deviceWrapDto("w1", enrolment.deviceWrap))),
                    ),
                ),
            )

            val step = repository.determineStep()

            assertEquals(EnrolmentStep.NeedsDeviceUnlock, step)
            // Unlike a permanent invalidation, nothing about the key or its local
            // record should be touched -- the fix is "unlock", not "re-enrol".
            assertTrue(deviceKeyProvider.exists())
            assertEquals("w1", enrolmentStore.deviceWrapId(host))
        }

    @Test
    fun `a lock-screen change re-enrols via recovery, then retires the dead wrap only after the new one exists`() =
        runTest {
            connectInstance()
            val devicePublicKey = deviceKeyProvider.ensureKeyPair()
            val enrolment = KeyCustody.enrolFirstDevice(devicePublicKey)
            enrolmentStore.saveDeviceWrapId(host, "w-stale")
            deviceKeyProvider.invalidateOnNextPrivateKeyUse = KeyPermanentlyInvalidatedException()

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(deviceWrapDto("w-stale", enrolment.deviceWrap))),
                    ),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(
                            listOf(deviceWrapDto("w-stale", enrolment.deviceWrap), KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1")),
                        ),
                    ),
                ),
            )

            val step = repository.determineStep()
            assertEquals(EnrolmentStep.NeedsRecoveryCode(reenrolling = true), step)
            assertEquals(false, deviceKeyProvider.exists()) // deleted on invalidation
            assertNull(enrolmentStore.deviceWrapId(host)) // cleared on invalidation

            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(recoveryWrapDto("rec1", enrolment.recoveryWrap))),
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-fresh","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repository.attemptRecovery(enrolment.recoveryCode.code)

            assertEquals(RecoveryAttemptResult.Success, result)
            assertEquals("w-fresh", enrolmentStore.deviceWrapId(host))
            assertEquals(5, server.requestCount)

            // Requests 1-2 were determineStep()'s (GET w-stale, then GET the full
            // list); requests 3-5 are attemptRecovery()'s: GET (full recovery
            // material), POST (new device wrap), DELETE (the stale one) -- in that
            // order, since deleting first would violate the server's "at least two
            // wrappings" invariant.
            server.takeRequest()
            server.takeRequest()
            val recoveryGetRequest = server.takeRequest()
            assertEquals("GET", recoveryGetRequest.method)
            val postRequest = server.takeRequest()
            assertEquals("POST", postRequest.method)
            val deleteRequest = server.takeRequest()
            assertEquals("DELETE", deleteRequest.method)
            assertTrue(deleteRequest.path!!.endsWith("/keys/w-stale"))
        }

    // ------------------------------------------------------------------
    // ensureHashSecret — plan step 2.7's dedup dependency, closing design.md open
    // question 4's Android half (the backend GET route itself is plan step 1.8).
    // ------------------------------------------------------------------

    @Test
    fun `ensureHashSecret fails cleanly with no master key, before any network call`() =
        runTest {
            connectInstance()

            val result = repository.ensureHashSecret()

            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `ensureHashSecret fetches, unwraps and caches it`() =
        runTest {
            connectInstance()
            val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
            masterKeyHolder.set(masterKey)
            val rawHashSecret = ByteArray(32) { (it * 3).toByte() }
            val wrapped = masterKey.wrapHashSecret(rawHashSecret)

            server.enqueue(
                MockResponse().setBody(
                    """{"encHashSecret":"${encode(wrapped)}","hashSecretKeyId":"mk-1"}""",
                ),
            )

            val result = repository.ensureHashSecret()

            assertTrue(result.isSuccess)
            assertArrayEquals(rawHashSecret, result.getOrThrow())
            assertArrayEquals(rawHashSecret, hashSecretHolder.current.value)
            assertEquals("/api/keys/hash-secret", server.takeRequest().path)
        }

    @Test
    fun `ensureHashSecret returns the cached value without a second network call`() =
        runTest {
            connectInstance()
            val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
            masterKeyHolder.set(masterKey)
            val rawHashSecret = ByteArray(32) { (it * 3).toByte() }
            hashSecretHolder.set(rawHashSecret)

            val result = repository.ensureHashSecret()

            assertTrue(result.isSuccess)
            assertArrayEquals(rawHashSecret, result.getOrThrow())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `ensureHashSecret fails, not crashes, when no device has ever PUT one`() =
        runTest {
            connectInstance()
            masterKeyHolder.set(MasterKey.of(ByteArray(32) { it.toByte() }))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

            val result = repository.ensureHashSecret()

            assertTrue(result.isFailure)
            assertNull(hashSecretHolder.current.value)
        }
}
