package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.DeviceEntity
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.14. [DeviceRepository] against a real Retrofit stack (MockWebServer) and
 * a real in-memory Room database — same pattern as [DeleteRepositoryTest].
 */
class DeviceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var db: AppDatabase
    private lateinit var repository: DeviceRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("device-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository = DeviceRepository(instanceStore, archivistApiFactory, db.deviceDao())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
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

    @Test
    fun `refresh replaces the local cache with the server's list`() =
        runTest {
            connectInstance()
            db.deviceDao().upsert(DeviceEntity("stale|device|1", "Stale", null, 0, "2026-01-01T00:00:00.000Z"))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"devices":[{"deviceKey":"canon|eos r5|001","label":"canon|eos r5|001","tzOffsetMin":540,"firstSeenAt":"2026-08-30T10:00:00.000Z","photoCount":3}]}""",
                ),
            )

            val result = repository.refresh()

            assertTrue(result.isSuccess)
            assertEquals(1, result.getOrThrow().size)
            assertNull(db.deviceDao().getByDeviceKey("stale|device|1"))
            assertEquals(540, db.deviceDao().getByDeviceKey("canon|eos r5|001")?.tzOffsetMin)
        }

    @Test
    fun `a failed refresh leaves the previous cache in place and is reported as failure`() =
        runTest {
            connectInstance()
            db.deviceDao().upsert(DeviceEntity("canon|eos r5|001", "R5", 540, 3, "2026-08-30T10:00:00.000Z"))
            server.enqueue(MockResponse().setResponseCode(500))

            val result = repository.refresh()

            assertTrue(result.isFailure)
            assertEquals(1, repository.cached().size)
        }

    @Test
    fun `tzOffsetMinFor reads the local cache with no network call`() =
        runTest {
            db.deviceDao().upsert(DeviceEntity("canon|eos r5|001", "R5", 540, 3, "2026-08-30T10:00:00.000Z"))
            assertEquals(540, repository.tzOffsetMinFor("canon|eos r5|001"))
            assertNull(repository.tzOffsetMinFor("never|seen|1"))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `update sends both fields and updates the local cache`() =
        runTest {
            connectInstance()
            db.deviceDao().upsert(DeviceEntity("canon|eos r5|001", "canon|eos r5|001", null, 3, "2026-08-30T10:00:00.000Z"))
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repository.update("canon|eos r5|001", "Dad's R5", 540)

            assertTrue(result.isSuccess)
            val request: RecordedRequest = server.takeRequest()
            assertEquals("PATCH", request.method)
            // The deviceKey's "|"s and spaces are percent-encoded on the wire (OkHttp
            // canonicalizes an unencoded @Url) -- decode before comparing.
            assertTrue(java.net.URLDecoder.decode(request.path.orEmpty(), "UTF-8").endsWith("/devices/canon|eos r5|001"))
            assertEquals("""{"label":"Dad's R5","tzOffsetMin":540}""", request.body.readUtf8())
            val cached = db.deviceDao().getByDeviceKey("canon|eos r5|001")
            assertEquals("Dad's R5", cached?.label)
            assertEquals(540, cached?.tzOffsetMin)
        }

    @Test
    fun `update with a null tzOffsetMin sends an explicit null, not an omitted field`() =
        runTest {
            connectInstance()
            db.deviceDao().upsert(DeviceEntity("canon|eos r5|001", "Dad's R5", 540, 3, "2026-08-30T10:00:00.000Z"))
            server.enqueue(MockResponse().setResponseCode(204))

            repository.update("canon|eos r5|001", "Dad's R5", null)

            val request = server.takeRequest()
            assertEquals("""{"label":"Dad's R5","tzOffsetMin":null}""", request.body.readUtf8())
        }

    @Test
    fun `remove deletes server-side and locally`() =
        runTest {
            connectInstance()
            db.deviceDao().upsert(DeviceEntity("canon|eos r5|001", "R5", 540, 3, "2026-08-30T10:00:00.000Z"))
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repository.remove("canon|eos r5|001")

            assertTrue(result.isSuccess)
            assertEquals("DELETE", server.takeRequest().method)
            assertNull(db.deviceDao().getByDeviceKey("canon|eos r5|001"))
        }
}
