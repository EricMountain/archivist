package fr.enry.archivist.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.DiscoveryClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.repo.InstanceRepository
import fr.enry.archivist.testutil.FakeDiscoveryApi
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var fakeApi: FakeDiscoveryApi
    private lateinit var viewModel: ConnectViewModel

    private val document =
        DiscoveryDocument(
            apiBase = "https://photos.example.com/api",
            region = "eu-west-1",
            cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "abc123"),
            cryptoVersion = 1,
            instanceName = "Home photos",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("connect-viewmodel-test").toFile()
        val dataStore =
            PreferenceDataStoreFactory.create(
                // Bound to the same virtual dispatcher as Main (below), so
                // `dispatcher.scheduler.advanceUntilIdle()` deterministically drains
                // DataStore's internal actor too — its default scope is real
                // Dispatchers.IO, which advanceUntilIdle() can't see or wait for.
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        val store = InstanceStore(dataStore, Json { ignoreUnknownKeys = true })
        fakeApi = FakeDiscoveryApi()
        val repository = InstanceRepository(DiscoveryClient(fakeApi), store)
        viewModel = ConnectViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `starts by checking for a stored instance, then needs connection when none exists`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.NeedsConnection(), viewModel.uiState.value)
        }

    @Test
    fun `a previously connected instance is picked up on start`() =
        runTest(dispatcher) {
            // Its own store, in its own temp dir — DataStore disallows more than one
            // instance over the same file, and `setUp()` already opened one over `tempDir`.
            val seedDir = Files.createTempDirectory("connect-viewmodel-seed-test").toFile()
            try {
                val dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(dispatcher),
                        produceFile = { File(seedDir, "instances.preferences_pb") },
                    )
                val store = InstanceStore(dataStore, Json { ignoreUnknownKeys = true })
                // Populate directly, as a prior session would have via connect().
                store.save("photos.example.com", document)

                val repository = InstanceRepository(DiscoveryClient(fakeApi), store)
                val fresh = ConnectViewModel(repository)
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(ConnectUiState.Connected("Home photos"), fresh.uiState.value)
            } finally {
                seedDir.deleteRecursively()
            }
        }

    @Test
    fun `successful connect transitions to Connected`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            fakeApi.response = { document }

            viewModel.connect("photos.example.com")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.Connected("Home photos"), viewModel.uiState.value)
        }

    @Test
    fun `an unreachable host surfaces HostNotFound, not a generic error`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            fakeApi.error = IOException("refused")

            viewModel.connect("typo.example.com")
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is ConnectUiState.NeedsConnection && state.error == ConnectError.HostNotFound)
        }

    @Test
    fun `a blank host is rejected before touching the network`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.connect("   ")
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is ConnectUiState.NeedsConnection && state.error == ConnectError.InvalidHost)
            assertEquals(null, fakeApi.lastUrl)
        }

    @Test
    fun `changeInstance backs a connected instance out to NeedsConnection, prefilled with its host`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            fakeApi.response = { document }
            viewModel.connect("photos.example.com")
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.changeInstance()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                ConnectUiState.NeedsConnection(prefillHost = "photos.example.com"),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `changeInstance is a no-op unless currently connected`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.changeInstance()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.NeedsConnection(), viewModel.uiState.value)
        }

    @Test
    fun `entering reviewer preview transitions from NeedsConnection`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.enterReviewerPreview()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.ReviewerPreview, viewModel.uiState.value)
        }

    @Test
    fun `reviewer preview is picked up on a fresh launch, like a stored instance`() =
        runTest(dispatcher) {
            // Own store, own temp dir — same reasoning as "a previously connected
            // instance is picked up on start" above: DataStore disallows a second
            // instance over the same file `setUp()` already opened.
            val seedDir = Files.createTempDirectory("connect-viewmodel-preview-seed-test").toFile()
            try {
                val seedStore =
                    InstanceStore(
                        PreferenceDataStoreFactory.create(
                            scope = CoroutineScope(dispatcher),
                            produceFile = { File(seedDir, "instances.preferences_pb") },
                        ),
                        Json { ignoreUnknownKeys = true },
                    )
                seedStore.setReviewerPreviewEnabled(true)

                val fresh = ConnectViewModel(InstanceRepository(DiscoveryClient(fakeApi), seedStore))
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(ConnectUiState.ReviewerPreview, fresh.uiState.value)
            } finally {
                seedDir.deleteRecursively()
            }
        }

    @Test
    fun `exiting reviewer preview clears the flag and returns to NeedsConnection`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.enterReviewerPreview()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.exitReviewerPreview()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.NeedsConnection(), viewModel.uiState.value)
        }

    @Test
    fun `entering reviewer preview is a no-op unless currently needing connection`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            fakeApi.response = { document }
            viewModel.connect("photos.example.com")
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.enterReviewerPreview()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(ConnectUiState.Connected("Home photos"), viewModel.uiState.value)
        }

    @Test
    fun `connecting again while already connecting is a no-op`() =
        runTest(dispatcher) {
            dispatcher.scheduler.advanceUntilIdle()
            fakeApi.response = { document }

            viewModel.connect("photos.example.com")
            // Fire a second call before the first's coroutine has run.
            viewModel.connect("photos2.example.com")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("https://photos.example.com/.well-known/archivist.json", fakeApi.lastUrl)
        }
}
