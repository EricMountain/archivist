package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.repo.DeviceRepository
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.14's Devices settings screen. Follows [fr.enry.archivist.ui.trash.TrashViewModelTest]'s
 * pattern (`StandardTestDispatcher` + a real `DeviceRepository`/MockWebServer/Room stack)
 * since [DevicesViewModel] loads via `init {}`, which runs on `viewModelScope` the
 * moment the class is constructed.
 */
class DevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: fr.enry.archivist.data.local.db.AppDatabase

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("devices-viewmodel-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    private fun newViewModel(): DevicesViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        val instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()
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
        return DevicesViewModel(DeviceRepository(instanceStore, archivistApiFactory, db.deviceDao()))
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
    fun `loads the device list on init`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"devices":[{"deviceKey":"canon|eos r5|001","label":"canon|eos r5|001","firstSeenAt":"2026-08-30T10:00:00.000Z","photoCount":3}]}""",
                ),
            )
            val viewModel = newViewModel()

            awaitState { (viewModel.uiState.value as? DevicesUiState.Loaded)?.devices?.isNotEmpty() == true }

            val state = viewModel.uiState.value as DevicesUiState.Loaded
            assertEquals(1, state.devices.size)
            assertEquals(3, state.devices[0].photoCount)
        }

    @Test
    fun `a failed load falls back to an empty cache with an error, not a crash`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(500))
            val viewModel = newViewModel()

            awaitState { (viewModel.uiState.value as? DevicesUiState.Loaded)?.error != null }

            val state = viewModel.uiState.value as DevicesUiState.Loaded
            assertTrue(state.devices.isEmpty())
            assertEquals("Couldn't refresh — showing the last known list.", state.error)
        }

    @Test
    fun `update refreshes the list from the server afterward`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"devices":[]}"""))
            val viewModel = newViewModel()
            awaitState { viewModel.uiState.value is DevicesUiState.Loaded }

            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"devices":[{"deviceKey":"canon|eos r5|001","label":"Dad's R5","tzOffsetMin":540,"firstSeenAt":"2026-08-30T10:00:00.000Z","photoCount":1}]}""",
                ),
            )

            viewModel.update("canon|eos r5|001", "Dad's R5", 540)
            awaitState { (viewModel.uiState.value as? DevicesUiState.Loaded)?.devices?.any { it.label == "Dad's R5" } == true }

            val state = viewModel.uiState.value as DevicesUiState.Loaded
            assertEquals(540, state.devices.single().tzOffsetMin)
        }

    @Test
    fun `remove refreshes the list from the server afterward`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"devices":[{"deviceKey":"canon|eos r5|001","label":"R5","firstSeenAt":"2026-08-30T10:00:00.000Z","photoCount":1}]}""",
                ),
            )
            val viewModel = newViewModel()
            awaitState { (viewModel.uiState.value as? DevicesUiState.Loaded)?.devices?.isNotEmpty() == true }

            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"devices":[]}"""))

            viewModel.remove("canon|eos r5|001")
            awaitState { (viewModel.uiState.value as? DevicesUiState.Loaded)?.devices?.isEmpty() == true }

            assertTrue((viewModel.uiState.value as DevicesUiState.Loaded).devices.isEmpty())
        }
}
