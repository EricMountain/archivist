package fr.enry.archivist.data.remote

import fr.enry.archivist.testutil.FakeDiscoveryApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

class DiscoveryClientTest {
    private val fakeApi = FakeDiscoveryApi()
    private val client = DiscoveryClient(fakeApi)

    private val sampleDocument =
        DiscoveryDocument(
            apiBase = "https://photos.example.com/api",
            region = "eu-west-1",
            cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "abc123"),
            cryptoVersion = 1,
            instanceName = "Home photos",
        )

    @Test
    fun `valid host and document succeeds`() =
        runTest {
            fakeApi.response = { sampleDocument }

            val result = client.fetch("photos.example.com")

            assertEquals(DiscoveryResult.Success("photos.example.com", sampleDocument), result)
            assertEquals("https://photos.example.com/.well-known/archivist.json", fakeApi.lastUrl)
        }

    @Test
    fun `explicit https prefix is stripped before dispatch`() =
        runTest {
            fakeApi.response = { sampleDocument }

            client.fetch("https://photos.example.com")

            assertEquals("https://photos.example.com/.well-known/archivist.json", fakeApi.lastUrl)
        }

    @Test
    fun `empty host is rejected without a network call`() =
        runTest {
            val result = client.fetch("   ")

            assertEquals(DiscoveryResult.InvalidHost, result)
            assertNull(fakeApi.lastUrl)
        }

    @Test
    fun `explicit http scheme is rejected outright, not upgraded`() =
        runTest {
            val result = client.fetch("http://photos.example.com")

            assertEquals(DiscoveryResult.InvalidHost, result)
            assertNull(fakeApi.lastUrl)
        }

    @Test
    fun `an unrelated scheme is also rejected`() =
        runTest {
            val result = client.fetch("ftp://photos.example.com")

            assertEquals(DiscoveryResult.InvalidHost, result)
            assertNull(fakeApi.lastUrl)
        }

    @Test
    fun `unreachable host maps to HostNotFound, not a generic failure`() =
        runTest {
            fakeApi.error = IOException("connection refused")

            val result = client.fetch("typo.example.com")

            assertEquals(DiscoveryResult.HostNotFound, result)
        }

    @Test
    fun `a non-2xx response maps to NotArchivist`() =
        runTest {
            fakeApi.error = HttpException(Response.error<Any>(404, "".toResponseBody()))

            val result = client.fetch("not-archivist.example.com")

            assertEquals(DiscoveryResult.NotArchivist, result)
        }

    @Test
    fun `a body that isn't the discovery document maps to NotArchivist`() =
        runTest {
            fakeApi.error = SerializationException("not json")

            val result = client.fetch("not-archivist.example.com")

            assertEquals(DiscoveryResult.NotArchivist, result)
        }

    @Test
    fun `a newer crypto version maps to ServerTooNew, not Success`() =
        runTest {
            fakeApi.response = { sampleDocument.copy(cryptoVersion = SUPPORTED_CRYPTO_VERSION + 1) }

            val result = client.fetch("photos.example.com")

            assertEquals(DiscoveryResult.ServerTooNew(SUPPORTED_CRYPTO_VERSION + 1), result)
        }

    @Test
    fun `crypto version equal to what this build supports still succeeds`() =
        runTest {
            fakeApi.response = { sampleDocument.copy(cryptoVersion = SUPPORTED_CRYPTO_VERSION) }

            val result = client.fetch("photos.example.com")

            assert(result is DiscoveryResult.Success)
        }
}
