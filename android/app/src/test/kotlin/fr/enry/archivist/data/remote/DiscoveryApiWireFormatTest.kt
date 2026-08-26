package fr.enry.archivist.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * [DiscoveryClient] assumes non-2xx responses surface as [HttpException] and
 * unparseable bodies surface as [SerializationException] — this proves those
 * assumptions against the real Retrofit/OkHttp/kotlinx.serialization stack, not
 * just against [fr.enry.archivist.testutil.FakeDiscoveryApi]'s stand-in.
 *
 * Uses plain http (not https): this exercises [DiscoveryApi] and the wire format
 * directly, bypassing [DiscoveryClient]'s https-only host normalization, which is
 * covered separately in [DiscoveryClientTest].
 */
class DiscoveryApiWireFormatTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DiscoveryApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val retrofit =
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        api = retrofit.create(DiscoveryApi::class.java)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses a real discovery document`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
                        {
                          "apiBase": "https://photos.example.com/api",
                          "region": "eu-west-1",
                          "cognito": { "userPoolId": "eu-west-1_XXXXXXXXX", "clientId": "abc123" },
                          "cryptoVersion": 1,
                          "instanceName": "Home photos"
                        }
                        """.trimIndent(),
                    )
                    .setHeader("Content-Type", "application/json"),
            )

            val doc = api.getDiscoveryDocument(server.url("/.well-known/archivist.json").toString())

            assertEquals("Home photos", doc.instanceName)
            assertEquals(1, doc.cryptoVersion)
            assertEquals("eu-west-1_XXXXXXXXX", doc.cognito.userPoolId)
        }

    @Test
    fun `a 404 surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = runCatching { api.getDiscoveryDocument(server.url("/nope").toString()) }

            assertTrue(result.exceptionOrNull() is HttpException)
        }

    @Test
    fun `a non-JSON body surfaces as SerializationException`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody("<html>not archivist</html>")
                    .setHeader("Content-Type", "text/html"),
            )

            val result = runCatching { api.getDiscoveryDocument(server.url("/").toString()) }

            assertTrue(result.exceptionOrNull() is SerializationException)
        }
}
