package fr.enry.archivist.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.AuthSession
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.AuthChallengeResponse
import fr.enry.archivist.data.remote.AuthenticationResult
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.StartWebAuthnRegistrationResponse
import fr.enry.archivist.data.repo.AuthRepository
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var fakeCognitoApi: FakeCognitoAuthApi
    private lateinit var tokenStore: TokenStore
    private lateinit var instanceStore: InstanceStore
    private lateinit var viewModel: SignInViewModel

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("signin-viewmodel-test").toFile()
        val json = Json { ignoreUnknownKeys = true }

        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        instanceStore = InstanceStore(dataStore, json)
        fakeCognitoApi = FakeCognitoAuthApi()
        tokenStore = TokenStore(FakeSharedPreferences(), json)
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = tokenStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
            )
        val repository =
            AuthRepository(instanceStore, CognitoAuthClient(fakeCognitoApi, json), tokenStore, archivistApiFactory)

        runTest(dispatcher) {
            instanceStore.save(
                host,
                DiscoveryDocument(
                    apiBase = server.url("/api").toString().trimEnd('/'),
                    region = "eu-west-1",
                    cognito =
                        DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                    cryptoVersion = 1,
                    instanceName = "Home photos",
                ),
            )
        }

        viewModel = SignInViewModel(repository, instanceStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun authResult() = AuthenticationResult("access", "id", "refresh", 3600, "Bearer")

    /**
     * `advanceUntilIdle()` alone only drains what's *already* queued on [dispatcher] —
     * it can't wait for a real network round-trip through OkHttp/MockWebServer, which
     * runs on OkHttp's own real thread pool and resumes the coroutine asynchronously,
     * at a real wall-clock moment `advanceUntilIdle()` has no way to wait for. Needed
     * only by tests whose action reaches `ArchivistApiFactory`'s real client; the
     * FakeCognitoAuthApi-only tests never cross that boundary and don't need it.
     */
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
    fun `no stored session starts at EnterUsername`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(SignInUiState.EnterUsername(), viewModel.uiState.value)
        }

    @Test
    fun `an existing valid session skips straight to SignedIn`() =
        runTest {
            tokenStore.save(
                host,
                AuthSession("a@example.com", "access", "id", "refresh", System.currentTimeMillis() + 3_600_000),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn, viewModel.uiState.value)
        }

    @Test
    fun `a registered passkey moves straight to the assertion ceremony`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(
                    challengeName = "WEB_AUTHN",
                    session = "sess-1",
                    challengeParameters = mapOf("CREDENTIAL_REQUEST_OPTIONS" to """{"challenge":"abc"}"""),
                )

            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SignInUiState.AwaitingPasskeyAssertion("a@example.com", "sess-1", """{"challenge":"abc"}"""),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `no registered passkey falls back to the password form`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")

            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SignInUiState.EnterPassword("a@example.com"), viewModel.uiState.value)
        }

    @Test
    fun `a successful passkey assertion signs in and calls bootstrap`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(
                    challengeName = "WEB_AUTHN",
                    session = "sess-1",
                    challengeParameters = mapOf("CREDENTIAL_REQUEST_OPTIONS" to """{"challenge":"abc"}"""),
                )
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            fakeCognitoApi.respondToAuthChallengeResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))

            viewModel.onPasskeyAssertionResult(Result.success("""{"id":"assertion"}"""))
            awaitState { viewModel.uiState.value == SignInUiState.SignedIn }

            assertEquals(SignInUiState.SignedIn, viewModel.uiState.value)
            assertEquals("access", tokenStore.get(host)?.accessToken)
        }

    @Test
    fun `a cancelled passkey ceremony falls back to the password form`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(
                    challengeName = "WEB_AUTHN",
                    session = "sess-1",
                    challengeParameters = mapOf("CREDENTIAL_REQUEST_OPTIONS" to """{"challenge":"abc"}"""),
                )
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onPasskeyAssertionResult(Result.failure(IOException("user cancelled")))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SignInUiState.EnterPassword("a@example.com"), viewModel.uiState.value)
        }

    @Test
    fun `a wrong password surfaces InvalidCredentials without changing screens`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            fakeCognitoApi.initiateAuthError =
                HttpException(
                    Response.error<Any>(
                        400,
                        """{"__type":"NotAuthorizedException","message":"bad"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                )
            viewModel.signInWithPassword("wrong-password")
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is SignInUiState.EnterPassword && state.error == SignInError.InvalidCredentials)
        }

    @Test
    fun `a first-time password sign-in leads to NEW_PASSWORD_REQUIRED`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "NEW_PASSWORD_REQUIRED", session = "sess-2")
            viewModel.signInWithPassword("temp-password")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SignInUiState.SetNewPassword("a@example.com", "sess-2"), viewModel.uiState.value)
        }

    @Test
    fun `completing NEW_PASSWORD_REQUIRED signs in and offers passkey registration`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "NEW_PASSWORD_REQUIRED", session = "sess-2")
            viewModel.signInWithPassword("temp-password")
            dispatcher.scheduler.advanceUntilIdle()

            fakeCognitoApi.respondToAuthChallengeResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            val options = buildJsonObject { put("challenge", "xyz") }
            fakeCognitoApi.startWebAuthnRegistrationResponse = StartWebAuthnRegistrationResponse(options)

            viewModel.setNewPassword("new-real-password")
            awaitState { viewModel.uiState.value is SignInUiState.AwaitingPasskeyRegistration }

            val state = viewModel.uiState.value
            assertTrue(state is SignInUiState.AwaitingPasskeyRegistration && !state.isOptional)
        }

    @Test
    fun `skipping optional passkey registration still counts as signed in`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()

            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            val options = buildJsonObject { put("challenge", "xyz") }
            fakeCognitoApi.startWebAuthnRegistrationResponse = StartWebAuthnRegistrationResponse(options)

            viewModel.signInWithPassword("existing-password")
            awaitState { viewModel.uiState.value is SignInUiState.AwaitingPasskeyRegistration }

            val awaiting = viewModel.uiState.value
            assertTrue(awaiting is SignInUiState.AwaitingPasskeyRegistration && awaiting.isOptional)

            viewModel.skipPasskeyRegistration()

            assertEquals(SignInUiState.SignedIn, viewModel.uiState.value)
        }

    @Test
    fun `a failed passkey registration doesn't undo an otherwise-successful sign-in`() =
        runTest {
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "SELECT_CHALLENGE", session = "sess-1")
            viewModel.continueWithUsername("a@example.com")
            dispatcher.scheduler.advanceUntilIdle()
            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            val options = buildJsonObject { put("challenge", "xyz") }
            fakeCognitoApi.startWebAuthnRegistrationResponse = StartWebAuthnRegistrationResponse(options)
            viewModel.signInWithPassword("existing-password")
            awaitState { viewModel.uiState.value is SignInUiState.AwaitingPasskeyRegistration }

            viewModel.onPasskeyRegistrationResult(Result.failure(IOException("no authenticator")))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn, viewModel.uiState.value)
        }
}
