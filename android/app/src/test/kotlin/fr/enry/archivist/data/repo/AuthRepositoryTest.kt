package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.AuthChallengeResponse
import fr.enry.archivist.data.remote.AuthenticationResult
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.CognitoAuthResult
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.PasskeyRegistrationStart
import fr.enry.archivist.data.remote.StartWebAuthnRegistrationResponse
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var fakeCognitoApi: FakeCognitoAuthApi
    private lateinit var tokenStore: TokenStore
    private lateinit var instanceStore: InstanceStore
    private lateinit var repository: AuthRepository

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("auth-repository-test").toFile()
        val json = Json { ignoreUnknownKeys = true }

        val dataStore =
            PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)

        fakeCognitoApi = FakeCognitoAuthApi()
        tokenStore = TokenStore(FakeSharedPreferences(), json)
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
                json = json,
                tokenStore = tokenStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
            )
        repository =
            AuthRepository(
                instanceStore = instanceStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
                tokenStore = tokenStore,
                archivistApiFactory = archivistApiFactory,
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

    private fun authResult(refreshToken: String? = "refresh") =
        AuthenticationResult("access", "id", refreshToken, 3600, "Bearer")

    @Test
    fun `a successful sign-in persists the session and calls bootstrap`() =
        runTest {
            connectInstance()
            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))

            val result = repository.signInWithPassword("a@example.com", "pw")

            assertTrue(result is CognitoAuthResult.SignedIn)
            assertEquals("access", tokenStore.get(host)?.accessToken)
            val bootstrapRequest = server.takeRequest()
            assertEquals("/api/session/bootstrap", bootstrapRequest.path)
            assertEquals("Bearer access", bootstrapRequest.getHeader("Authorization"))
        }

    @Test
    fun `bootstrap is idempotent-safe to call on every sign-in — no client-side first-time tracking`() =
        runTest {
            connectInstance()
            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":false}"""))

            repository.signInWithPassword("a@example.com", "pw")
            repository.signInWithPassword("a@example.com", "pw")

            assertEquals(2, server.requestCount)
        }

    @Test
    fun `NEW_PASSWORD_REQUIRED does not persist a session or call bootstrap yet`() =
        runTest {
            connectInstance()
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(challengeName = "NEW_PASSWORD_REQUIRED", session = "sess-1")

            val result = repository.signInWithPassword("a@example.com", "temp-pw")

            assertEquals(CognitoAuthResult.NewPasswordRequired("sess-1"), result)
            assertEquals(null, tokenStore.get(host))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `completing a new password persists the session and bootstraps`() =
        runTest {
            connectInstance()
            fakeCognitoApi.respondToAuthChallengeResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))

            val result = repository.completeNewPassword("a@example.com", "new-pw", "sess-1")

            assertTrue(result is CognitoAuthResult.SignedIn)
            assertNotNull(tokenStore.get(host))
        }

    @Test
    fun `passkey registration uses the signed-in session's access token`() =
        runTest {
            connectInstance()
            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            repository.signInWithPassword("a@example.com", "pw")

            val options = buildJsonObject { put("challenge", "xyz") }
            fakeCognitoApi.startWebAuthnRegistrationResponse = StartWebAuthnRegistrationResponse(options)

            val start = repository.startPasskeyRegistration()

            assertTrue(start is PasskeyRegistrationStart.Options)
            assertEquals("access", fakeCognitoApi.lastStartWebAuthnRegistrationRequest?.accessToken)
        }

    @Test
    fun `starting passkey registration while signed out fails cleanly, not with a crash`() =
        runTest {
            connectInstance()

            val start = repository.startPasskeyRegistration()

            assertTrue(start is PasskeyRegistrationStart.Failed)
        }

    @Test
    fun `sign-out revokes the refresh token and clears the local session`() =
        runTest {
            connectInstance()
            fakeCognitoApi.initiateAuthResponse = AuthChallengeResponse(authenticationResult = authResult())
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))
            repository.signInWithPassword("a@example.com", "pw")

            repository.signOut()

            assertEquals(null, tokenStore.get(host))
            assertEquals("refresh", fakeCognitoApi.lastRevokeTokenRequest?.token)
        }
}
