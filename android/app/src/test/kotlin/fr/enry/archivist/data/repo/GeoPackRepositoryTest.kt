package fr.enry.archivist.data.repo

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.domain.GeoPack
import fr.enry.archivist.domain.GeoPackSet
import fr.enry.archivist.domain.GeoTiles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * [GeoPackRepository] over the synthetic fixture tile set (tools/geopack/testdata), served
 * by a MockWebServer at the repository's own `https://<host>/geo/...` URLs (an interceptor
 * redirects them), with `noBackupFilesDir` in a temp directory.
 */
class GeoPackRepositoryTest {
    private val host = "photos.example.com"
    private val testdata by lazy { File(requireNotNull(System.getProperty("archivist.geopack.dir"))).resolve("testdata") }
    private val manifestText by lazy { testdata.resolve("fixture-manifest.json").readText() }
    private val tileKeys by lazy { Json.parseToJsonElement(manifestText).jsonObject["tiles"]!!.jsonObject.keys }
    private val oracle by lazy {
        GeoPackSet(listOf("fixture-a.bin", "fixture-b.bin").map { GeoPack.read(testdata.resolve(it).readBytes()) })
    }

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private val requests = CopyOnWriteArrayList<String>()
    private var manifestBody: () -> MockResponse = { MockResponse().setBody(manifestText) }
    private var tamper: String? = null

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("geopack-repo-test").toFile()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    requests += path
                    if (path == "/geo/manifest.json") return manifestBody()
                    val file = testdata.resolve(path.removePrefix("/geo/"))
                    if (!path.startsWith("/geo/") || !file.isFile) return MockResponse().setResponseCode(404)
                    val bytes = file.readBytes()
                    if (path == tamper) bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
                    return MockResponse().setBody(Buffer().write(bytes))
                }
            }
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        instanceStore = InstanceStore(dataStore, json)
        runBlocking {
            instanceStore.save(
                host,
                DiscoveryDocument(
                    apiBase = "https://$host/api",
                    region = "eu-west-1",
                    cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                    cryptoVersion = 1,
                    instanceName = "Home photos",
                ),
            )
        }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
        tempDir.deleteRecursively()
    }

    private fun repository(): GeoPackRepository {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val url = chain.request().url
                    val local = url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                    chain.proceed(chain.request().newBuilder().url(local).build())
                }.build()
        val context = mock<Context> { on { noBackupFilesDir } doReturn tempDir.resolve("no_backup") }
        return GeoPackRepository(instanceStore, client, context)
    }

    private fun tileRequests() = requests.filter { it.endsWith(".bin") }

    @Test
    fun `a point downloads only its own tile and gets the same result as the merged packs`() =
        runBlocking {
            val (lat, lon) = 45.0110 to 5.0110
            val got = repository().packsFor(lat, lon)
            val key = GeoTiles.find(tileKeys, lat, lon)!!
            assertEquals(listOf("/geo/fixture/$key.bin"), tileRequests())
            got as GeoPacks.Covered
            assertEquals("fixture", got.set)
            assertEquals(oracle.lookup(lat, lon), got.packs.lookup(lat, lon))
        }

    @Test
    fun `a tile is reused in memory, another tile is fetched once`() =
        runBlocking {
            val repo = repository()
            val a = 45.0110 to 5.0110
            val b = 45.0470 to 5.4500
            assertNotEquals(GeoTiles.find(tileKeys, a.first, a.second), GeoTiles.find(tileKeys, b.first, b.second))
            repo.packsFor(a.first, a.second)
            repo.packsFor(a.first + 0.0001, a.second)
            repo.packsFor(b.first, b.second)
            repo.packsFor(a.first, a.second)
            assertEquals(2, tileRequests().size)
            assertEquals(1, requests.count { it == "/geo/manifest.json" })
        }

    @Test
    fun `no tile for the point is Uncovered, with nothing downloaded`() =
        runBlocking {
            assertEquals(GeoPacks.Uncovered("fixture"), repository().packsFor(-33.8688, 151.2093))
            assertEquals(emptyList<String>(), tileRequests())
        }

    @Test
    fun `an instance publishing nothing is None`() =
        runBlocking {
            manifestBody = { MockResponse().setResponseCode(403) }
            assertEquals(GeoPacks.None, repository().packsFor(45.0110, 5.0110))
        }

    @Test
    fun `a tile that fails its sha256 is Unavailable`() =
        runBlocking {
            tamper = "/geo/fixture/${GeoTiles.find(tileKeys, 45.0110, 5.0110)}.bin"
            assertEquals(GeoPacks.Unavailable, repository().packsFor(45.0110, 5.0110))
        }

    @Test
    fun `a manifest in the old format is Unavailable`() =
        runBlocking {
            manifestBody = { MockResponse().setBody("""{"format":1,"set":"fixture","packs":[]}""") }
            assertEquals(GeoPacks.Unavailable, repository().packsFor(45.0110, 5.0110))
        }

    @Test
    fun `offline, the saved manifest and cached tile still answer`() =
        runBlocking {
            repository().packsFor(45.0110, 5.0110)
            server.shutdown()
            val got = repository().packsFor(45.0110, 5.0110) // a fresh repository: nothing in memory
            assertTrue(got is GeoPacks.Covered, "$got")
            assertEquals(oracle.lookup(45.0110, 5.0110), (got as GeoPacks.Covered).packs.lookup(45.0110, 5.0110))
        }
}
