package fr.enry.archivist.ui.trash

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.13. [TrashViewModel.load] against a real Retrofit stack (MockWebServer),
 * confirming the blocked-re-upload fields survive the DTO-to-[TrashItem] conversion —
 * the warning text itself is [TrashScreenTest]'s job, kept separate since it needs no
 * network/ViewModel at all. Follows [fr.enry.archivist.ui.onboarding.SignInViewModelTest]'s
 * own pattern for a `StandardTestDispatcher` + real `ArchivistApiFactory`/MockWebServer
 * combination — `advanceUntilIdle()` alone can't wait for OkHttp's real thread pool.
 */
class TrashViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var viewModel: TrashViewModel

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("trash-viewmodel-test").toFile()
        val json = Json { ignoreUnknownKeys = true }

        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        instanceStore = InstanceStore(dataStore, json)
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

        viewModel = TrashViewModel(instanceStore, archivistApiFactory)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** See `SignInViewModelTest.awaitState`'s own doc: `advanceUntilIdle()` alone can't
     * wait for a real network round-trip through OkHttp/MockWebServer. */
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
    fun `load surfaces the blocked-re-upload warning fields when present`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "items": [
                        {
                          "photoId": "p1",
                          "takenAt": "2026-08-30T10:00:00.000Z",
                          "thumbs": {},
                          "encDek": "dek",
                          "encKeyId": "mk-1",
                          "width": 100,
                          "height": 100,
                          "mime": "image/jpeg",
                          "tzOffsetMin": 0,
                          "status": "ready",
                          "blockedAttempts": 3,
                          "lastAttemptAt": "2026-09-01T10:00:00.000Z",
                          "lastAttemptBy": "home-server"
                        },
                        {
                          "photoId": "p2",
                          "takenAt": "2026-08-31T10:00:00.000Z",
                          "thumbs": {},
                          "encDek": "dek",
                          "encKeyId": "mk-1",
                          "width": 100,
                          "height": 100,
                          "mime": "image/jpeg",
                          "tzOffsetMin": 0,
                          "status": "ready"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            viewModel.load()
            awaitState { viewModel.uiState.value is TrashUiState.Loaded }

            val state = viewModel.uiState.value as TrashUiState.Loaded
            assertEquals(2, state.items.size)
            val warned = state.items.single { it.photoId == "p1" }
            assertEquals(3, warned.blockedAttempts)
            assertEquals("home-server", warned.lastAttemptBy)
            val clean = state.items.single { it.photoId == "p2" }
            assertNull(clean.blockedAttempts)
        }

    @Test
    fun `a failed fetch reports an error rather than an empty list`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            viewModel.load()
            awaitState { viewModel.uiState.value is TrashUiState.Error }

            assertTrue(viewModel.uiState.value is TrashUiState.Error)
        }
}
