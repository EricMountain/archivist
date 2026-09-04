package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeDeviceKeyProvider
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.14's Keys settings screen — list/remove wrappings, and the recovery-code
 * regeneration sub-flow, which exercises real Argon2id/AES-KW through [EnrolmentRepository]
 * exactly as [fr.enry.archivist.data.repo.EnrolmentRepositoryTest]'s own recovery tests do.
 */
class KeysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var masterKeyHolder: MasterKeyHolder

    private val host = "photos.example.com"
    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("keys-viewmodel-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun newViewModel(unlocked: Boolean = true): KeysViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        val instanceStore = InstanceStore(dataStore, json)
        masterKeyHolder = MasterKeyHolder().apply { if (unlocked) set(masterKey) }
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        runTest(dispatcher) {
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
        val enrolmentRepository =
            EnrolmentRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                enrolmentStore = EnrolmentStore(FakeSharedPreferences()),
                deviceKeystore = FakeDeviceKeyProvider(),
                masterKeyHolder = masterKeyHolder,
                hashSecretHolder = HashSecretHolder(),
            )
        return KeysViewModel(enrolmentRepository)
    }

    private fun awaitUntil(
        timeoutMs: Long = 2000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(5)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `loads the wrap list on init`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"wraps":[{"wrapId":"w1","kind":"device","label":"Pixel","masterKeyVer":"mk-1"}]}""",
                ),
            )
            val viewModel = newViewModel()

            awaitUntil { (viewModel.uiState.value as? KeysUiState.Loaded)?.wraps?.isNotEmpty() == true }

            assertEquals("Pixel", (viewModel.uiState.value as KeysUiState.Loaded).wraps.single().label)
        }

    @Test
    fun `removeKey refreshes the list on success`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"wraps":[{"wrapId":"w1","kind":"device","label":"Pixel","masterKeyVer":"mk-1"}]}""",
                ),
            )
            val viewModel = newViewModel()
            awaitUntil { viewModel.uiState.value is KeysUiState.Loaded }

            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"wraps":[]}"""))

            viewModel.removeKey("w1")
            awaitUntil { (viewModel.uiState.value as? KeysUiState.Loaded)?.wraps?.isEmpty() == true }

            assertTrue((viewModel.uiState.value as KeysUiState.Loaded).wraps.isEmpty())
        }

    @Test
    fun `removeKey surfaces a server-side refusal rather than silently doing nothing`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"wraps":[]}"""))
            val viewModel = newViewModel()
            awaitUntil { viewModel.uiState.value is KeysUiState.Loaded }

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"at least two key wrappings must remain"}"""))

            viewModel.removeKey("w1")
            awaitUntil { (viewModel.uiState.value as? KeysUiState.Loaded)?.error != null }

            assertNotNull((viewModel.uiState.value as KeysUiState.Loaded).error)
        }

    @Test
    fun `regenerating the recovery code shows a code, then a mismatched confirmation is rejected`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()

            viewModel.beginRecoveryRegeneration()
            val showing = viewModel.regenState.value
            assertTrue(showing is RecoveryRegenState.ShowingCode)

            viewModel.proceedToConfirmation()
            viewModel.confirmTypedCode("obviously-wrong-code")

            val confirming = viewModel.regenState.value as RecoveryRegenState.Confirming
            assertNotNull(confirming.error)
        }

    @Test
    fun `regenerating the recovery code with the right confirmation posts new and deletes old`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"wraps":[{"wrapId":"w1","kind":"device","label":"Pixel","masterKeyVer":"mk-1"},{"wrapId":"w2","kind":"recovery","label":"Recovery code","masterKeyVer":"mk-1"}]}""",
                ),
            )
            val viewModel = newViewModel()
            awaitUntil { viewModel.uiState.value is KeysUiState.Loaded }

            viewModel.beginRecoveryRegeneration()
            val code = (viewModel.regenState.value as RecoveryRegenState.ShowingCode).formattedCode
            viewModel.proceedToConfirmation()

            // The GET /keys to find the stale recovery wrapId, the POST of the new
            // one, then the DELETE of w2 -- then a fresh GET from KeysViewModel's own
            // post-success refresh().
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"wraps":[{"wrapId":"w1","kind":"device","label":"Pixel","masterKeyVer":"mk-1"},{"wrapId":"w2","kind":"recovery","label":"Recovery code","masterKeyVer":"mk-1"}]}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w3","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"wraps":[{"wrapId":"w1","kind":"device","label":"Pixel","masterKeyVer":"mk-1"},{"wrapId":"w3","kind":"recovery","label":"Recovery code","masterKeyVer":"mk-1"}]}""",
                ),
            )

            viewModel.confirmTypedCode(code)
            // Hidden fires before the post-success refresh() (its own separate
            // viewModelScope.launch) finishes its real network round trip -- wait for
            // that too, not just the regenState flip, or this can observe the list
            // before it's updated.
            awaitUntil {
                viewModel.regenState.value == RecoveryRegenState.Hidden &&
                    (viewModel.uiState.value as? KeysUiState.Loaded)?.wraps?.any { it.wrapId == "w3" } == true
            }

            assertEquals(RecoveryRegenState.Hidden, viewModel.regenState.value)
            val wraps = (viewModel.uiState.value as KeysUiState.Loaded).wraps
            assertTrue(wraps.any { it.wrapId == "w3" })
            assertTrue(wraps.none { it.wrapId == "w2" })
        }
}
