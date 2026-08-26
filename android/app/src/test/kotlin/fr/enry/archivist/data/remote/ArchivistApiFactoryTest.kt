package fr.enry.archivist.data.remote

import fr.enry.archivist.data.local.AuthSession
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException

/**
 * Exercises [ArchivistAuthInterceptor]/[ArchivistAuthenticator] against a real
 * OkHttp/Retrofit stack and [MockWebServer] — the refresh-on-401 logic couldn't be
 * verified live (would need a real expired token), so this is the actual coverage
 * for plan step 2.4's "refreshes on 401 exactly once" requirement.
 */
class ArchivistApiFactoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var fakeCognitoApi: FakeCognitoAuthApi
    private lateinit var factory: ArchivistApiFactory

    private val host = "photos.example.com"
    private val region = "eu-west-1"
    private val clientId = "client-id"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        tokenStore = TokenStore(FakeSharedPreferences(), json)
        fakeCognitoApi = FakeCognitoAuthApi()
        factory =
            ArchivistApiFactory(
                baseOkHttpClient =
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .build(),
                json = json,
                tokenStore = tokenStore,
                cognitoAuthClient = CognitoAuthClient(fakeCognitoApi, json),
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun saveSession(accessToken: String) {
        tokenStore.save(
            host,
            AuthSession(
                username = "a@example.com",
                accessToken = accessToken,
                idToken = "id",
                refreshToken = "refresh-token",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
            ),
        )
    }

    @Test
    fun `attaches the stored bearer token to every request`() =
        runTest {
            saveSession("access-1")
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))

            val api = factory.create(host, region, clientId)
            api.postSessionBootstrap(server.url("/session/bootstrap").toString(), SessionBootstrapRequest())

            val recorded = server.takeRequest()
            assertEquals("Bearer access-1", recorded.getHeader("Authorization"))
        }

    @Test
    fun `refreshes on 401 and retries exactly once, successfully`() =
        runTest {
            saveSession("expired-access")
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(
                    authenticationResult =
                        AuthenticationResult(
                            accessToken = "fresh-access",
                            idToken = "fresh-id",
                            refreshToken = null,
                            expiresIn = 3600,
                            tokenType = "Bearer",
                        ),
                )
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":false}"""))

            val api = factory.create(host, region, clientId)
            val result = api.postSessionBootstrap(server.url("/session/bootstrap").toString(), SessionBootstrapRequest())

            assertEquals("u1", result.userId)
            assertEquals(1, fakeCognitoApi.initiateAuthCallCount)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer expired-access", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-access", server.takeRequest().getHeader("Authorization"))
            // The refreshed token is now what's persisted for next time.
            assertEquals("fresh-access", tokenStore.get(host)?.accessToken)
        }

    @Test
    fun `a refresh that itself fails clears the session and gives up`() =
        runTest {
            saveSession("expired-access")
            fakeCognitoApi.initiateAuthError =
                HttpException(
                    retrofit2.Response.error<Any>(
                        400,
                        """{"__type":"NotAuthorizedException","message":"Invalid Refresh Token"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                )
            server.enqueue(MockResponse().setResponseCode(401))

            val api = factory.create(host, region, clientId)
            assertThrows(HttpException::class.java) {
                kotlinx.coroutines.runBlocking {
                    api.postSessionBootstrap(server.url("/session/bootstrap").toString(), SessionBootstrapRequest())
                }
            }

            assertEquals(1, server.requestCount) // authenticator gave up, no second attempt
            assertEquals(null, tokenStore.get(host))
        }

    @Test
    fun `never retries more than once, even if the retry also gets 401`() =
        runTest {
            saveSession("expired-access")
            fakeCognitoApi.initiateAuthResponse =
                AuthChallengeResponse(
                    authenticationResult =
                        AuthenticationResult(
                            accessToken = "still-rejected",
                            idToken = "id",
                            refreshToken = null,
                            expiresIn = 3600,
                            tokenType = "Bearer",
                        ),
                )
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))

            val api = factory.create(host, region, clientId)
            assertThrows(HttpException::class.java) {
                kotlinx.coroutines.runBlocking {
                    api.postSessionBootstrap(server.url("/session/bootstrap").toString(), SessionBootstrapRequest())
                }
            }

            // Exactly one retry attempt — not a third request from a refresh loop.
            assertEquals(2, server.requestCount)
            assertEquals(1, fakeCognitoApi.initiateAuthCallCount)
        }

    @Test
    fun `no stored session means no Authorization header at all`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"userId":"u1","ownerId":"o1","created":true}"""))

            val api = factory.create(host, region, clientId)
            api.postSessionBootstrap(server.url("/session/bootstrap").toString(), SessionBootstrapRequest())

            assertEquals(null, server.takeRequest().getHeader("Authorization"))
        }
}
