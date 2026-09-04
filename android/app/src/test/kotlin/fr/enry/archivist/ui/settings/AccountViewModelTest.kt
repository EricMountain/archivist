package fr.enry.archivist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.AuthSession
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.repo.AccountRepository
import fr.enry.archivist.data.repo.AuthRepository
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.data.repo.MasterKeyHolder
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.14's Account settings screen. `acknowledgeSessionEnded`'s own tests are
 * the important ones here: this `ViewModel` is Activity-scoped (no nav library — see
 * `android/AGENTS.md`'s `DetailViewModel` note), so a `sessionEnded` flag left `true`
 * after one sign-out/delete would fire [AccountScreen]'s `LaunchedEffect` again on the
 * very next visit, even after signing back in — found and fixed in the same pass this
 * screen was written, not a pre-existing bug.
 */
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var db: AppDatabase
    private lateinit var fakeCognitoApi: FakeCognitoAuthApi
    private lateinit var viewModel: AccountViewModel

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("account-viewmodel-test").toFile()

        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()
        fakeCognitoApi = FakeCognitoAuthApi()
        val tokenStore = TokenStore(FakeSharedPreferences(), json)
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = tokenStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
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
            tokenStore.save(
                host,
                AuthSession(
                    username = "alice",
                    accessToken = "access",
                    idToken = "id",
                    refreshToken = "refresh",
                    accessTokenExpiresAt = Long.MAX_VALUE,
                ),
            )
        }

        val authRepository =
            AuthRepository(
                instanceStore = instanceStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
                tokenStore = tokenStore,
                archivistApiFactory = archivistApiFactory,
            )
        val accountRepository =
            AccountRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                authRepository = authRepository,
                enrolmentStore = EnrolmentStore(FakeSharedPreferences()),
                masterKeyHolder = MasterKeyHolder(),
                hashSecretHolder = HashSecretHolder(),
                appDatabase = db,
            )
        viewModel = AccountViewModel(authRepository, accountRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
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
    fun `signOut ends the session`() =
        runTest(dispatcher) {
            viewModel.signOut()
            awaitUntil { viewModel.uiState.value.sessionEnded }

            assertTrue(viewModel.uiState.value.sessionEnded)
        }

    @Test
    fun `acknowledgeSessionEnded resets to a fresh state -- the retained-ViewModel fix`() =
        runTest(dispatcher) {
            viewModel.signOut()
            awaitUntil { viewModel.uiState.value.sessionEnded }

            viewModel.acknowledgeSessionEnded()

            // A later visit to this same (Activity-scoped) instance must not
            // immediately re-fire "session ended" just because it once was.
            assertEquals(AccountUiState(), viewModel.uiState.value)
            assertFalse(viewModel.uiState.value.sessionEnded)
        }

    @Test
    fun `deleteAccount success ends the session`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"userId":"u1","ownerId":"o1","created":false}"""))
            server.enqueue(MockResponse().setResponseCode(200))

            viewModel.deleteAccount()
            awaitUntil { viewModel.uiState.value.sessionEnded }

            assertTrue(viewModel.uiState.value.sessionEnded)
        }

    @Test
    fun `deleteAccount failure surfaces an error without ending the session`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"userId":"u1","ownerId":"o1","created":false}"""))
            server.enqueue(MockResponse().setResponseCode(500))

            viewModel.deleteAccount()
            awaitUntil { viewModel.uiState.value.error != null }

            assertNotNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.sessionEnded)
        }
}
