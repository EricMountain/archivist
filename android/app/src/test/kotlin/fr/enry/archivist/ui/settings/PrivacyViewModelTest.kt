package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.repo.OwnerSettingsRepository
import fr.enry.archivist.testutil.FakeCognitoAuthApi
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.18's Settings > Privacy section. Same `StandardTestDispatcher` +
 * MockWebServer pattern as [DevicesViewModelTest], since [PrivacyViewModel] also loads
 * via `init {}`.
 */
class PrivacyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("privacy-viewmodel-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun newViewModel(): PrivacyViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        val instanceStore = InstanceStore(dataStore, json)
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
        return PrivacyViewModel(OwnerSettingsRepository(instanceStore, archivistApiFactory))
    }

    private fun awaitState(
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
    fun `loads the current setting on init`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"homeTz":"UTC","stripLocationOnUpload":true}"""))
            val viewModel = newViewModel()

            awaitState { viewModel.uiState.value is PrivacyUiState.Loaded }

            val state = viewModel.uiState.value as PrivacyUiState.Loaded
            assertTrue(state.stripLocationOnUpload)
        }

    @Test
    fun `a failed load falls back to off with an error, not a crash`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(500))
            val viewModel = newViewModel()

            awaitState { (viewModel.uiState.value as? PrivacyUiState.Loaded)?.error != null }

            val state = viewModel.uiState.value as PrivacyUiState.Loaded
            assertFalse(state.stripLocationOnUpload)
            assertEquals("Couldn't load — try again.", state.error)
        }

    @Test
    fun `toggling on persists via PATCH and reflects the new value`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"homeTz":"UTC","stripLocationOnUpload":false}"""))
            val viewModel = newViewModel()
            awaitState { viewModel.uiState.value is PrivacyUiState.Loaded }

            server.enqueue(MockResponse().setResponseCode(204))
            viewModel.setStripLocationOnUpload(true)
            // Not just "...stripLocationOnUpload" -- the optimistic update sets that
            // immediately, before the PATCH even lands; wait for isSaving to clear too,
            // or this races the network call and can observe the mid-flight state.
            awaitState { (viewModel.uiState.value as PrivacyUiState.Loaded).let { it.stripLocationOnUpload && !it.isSaving } }

            val state = viewModel.uiState.value as PrivacyUiState.Loaded
            assertTrue(state.stripLocationOnUpload)
            assertFalse(state.isSaving)
            assertEquals("GET", server.takeRequest().method) // init's own load
            assertEquals("PATCH", server.takeRequest().method) // the toggle itself
        }

    @Test
    fun `a failed save reverts the switch and shows an error`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"homeTz":"UTC","stripLocationOnUpload":false}"""))
            val viewModel = newViewModel()
            awaitState { viewModel.uiState.value is PrivacyUiState.Loaded }

            server.enqueue(MockResponse().setResponseCode(500))
            viewModel.setStripLocationOnUpload(true)
            awaitState { (viewModel.uiState.value as? PrivacyUiState.Loaded)?.error != null }

            val state = viewModel.uiState.value as PrivacyUiState.Loaded
            assertFalse(state.stripLocationOnUpload) // reverted, not left on
            assertEquals("Couldn't save — try again.", state.error)
        }
}
