package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.PagingSource
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.assertThrows
import retrofit2.HttpException

/** Geolocation plan step 8: the place vocabulary's ETag cache (the same mechanism as
 * `syncHistogram`, see [PhotoRepositoryTest]) and the facet endpoint's [FacetPagingSource]. */
class PlaceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var instanceStore: InstanceStore
    private lateinit var factory: ArchivistApiFactory
    private lateinit var repository: PlaceRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("place-repository-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        db = buildTestDatabase()
        factory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        repository = PlaceRepository(db, instanceStore, factory, json)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    private fun apiBase() = server.url("/api").toString().trimEnd('/')

    private suspend fun connectInstance() {
        instanceStore.save(
            host,
            DiscoveryDocument(
                apiBase = apiBase(),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private val placesBody =
        """{"countries":[{"cc":"FK","n":1},{"cc":"JP","n":3}],""" +
            """"regions":[{"id":"JP.22","name":"Kyoto","cc":"JP","n":2}],""" +
            """"localities":[{"id":"1857910","name":"Kyoto","regionName":"Kyoto","cc":"JP","n":2},""" +
            """{"id":"3426691","name":"Stanley","cc":"FK","n":1}],"total":4,"version":4}"""

    @Test
    fun `syncVocabulary caches the body, then replays its etag and honours a 304`() =
        runTest {
            connectInstance()
            assertNull(repository.vocabulary().first())
            server.enqueue(MockResponse().setResponseCode(200).setHeader("etag", "\"pl-4\"").setBody(placesBody))

            repository.syncVocabulary()
            val first = server.takeRequest()
            assertEquals("/api/places", first.path)
            assertNull(first.getHeader("If-None-Match"))
            val cached = repository.vocabulary().first()!!
            assertEquals(listOf("FK", "JP"), cached.countries.map { it.cc })
            // No regionName for Stanley: absent, not "".
            assertNull(cached.localities.single { it.id == "3426691" }.regionName)
            assertEquals("Kyoto", cached.localities.single { it.id == "1857910" }.regionName)
            assertEquals(4L, cached.version)

            server.enqueue(MockResponse().setResponseCode(304))
            repository.syncVocabulary()
            assertEquals("\"pl-4\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(cached, repository.vocabulary().first())
        }

    @Test
    fun `a changed vocabulary replaces the cached one and its etag`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setHeader("etag", "\"pl-4\"").setBody(placesBody))
            repository.syncVocabulary()
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("etag", "\"pl-5\"")
                    .setBody("""{"countries":[{"cc":"JP","n":3}],"regions":[],"localities":[],"total":3,"version":5}"""),
            )
            repository.syncVocabulary()
            server.takeRequest()
            assertEquals("\"pl-4\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(listOf("JP"), repository.vocabulary().first()!!.countries.map { it.cc })

            server.enqueue(MockResponse().setResponseCode(304))
            repository.syncVocabulary()
            assertEquals("\"pl-5\"", server.takeRequest().getHeader("If-None-Match"))
        }

    @Test
    fun `a failed sync throws and leaves the cache for offline use`() =
        runTest {
            connectInstance()
            server.enqueue(MockResponse().setResponseCode(200).setHeader("etag", "\"pl-4\"").setBody(placesBody))
            repository.syncVocabulary()

            server.enqueue(MockResponse().setResponseCode(500))
            assertThrows<HttpException> { repository.syncVocabulary() }
            assertEquals(2, repository.vocabulary().first()!!.countries.size)
        }

    private fun facetJson(
        photoId: String,
        takenAt: String,
    ) = """{"photoId":"$photoId","facetType":"PLACE","facetValue":"1857910","takenAt":"$takenAt",""" +
        """"thumbs":{"256":{"bucket":"derived","key":"th/o1/$photoId/256","iv":"aXY=","bytes":100}},""" +
        """"encDek":"ZGVr","encKeyId":"mk-1","width":4000,"height":3000,"mime":"image/jpeg","tzOffsetMin":540,"status":"ready"}"""

    @Test
    fun `the facet paging source follows the server's cursor, newest first`() =
        runTest {
            connectInstance()
            val api = factory.create(host, "eu-west-1", "client-id")
            val url = facetUrl(apiBase(), "PLACE", "1857910")
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"items":[${facetJson("A3", "2026-07-14T10:05:41.000Z")}],"cursor":"c1"}"""),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[${facetJson("A1", "2026-07-14T09:22:05.000Z")}]}"""))
            val source = FacetPagingSource(api, url)

            val first = source.load(PagingSource.LoadParams.Refresh(null, 60, false)) as PagingSource.LoadResult.Page
            assertEquals(listOf("A3"), first.data.map { it.photoId })
            assertEquals("c1", first.nextKey)
            assertNull(first.prevKey)
            val req1 = server.takeRequest()
            assertEquals("/api/facets/PLACE/1857910?limit=60", req1.path)

            val second = source.load(PagingSource.LoadParams.Append("c1", 60, false)) as PagingSource.LoadResult.Page
            assertEquals(listOf("A1"), second.data.map { it.photoId })
            assertNull(second.nextKey)
            assertEquals("/api/facets/PLACE/1857910?cursor=c1&limit=60", server.takeRequest().path)

            assertEquals(
                PhotoEntity(
                    photoId = "A3",
                    takenAt = "2026-07-14T10:05:41.000Z",
                    tzOffsetMin = 540,
                    mime = "image/jpeg",
                    width = 4000,
                    height = 3000,
                    status = AssetStatus.READY,
                    thumbs = mapOf(256 to fr.enry.archivist.data.local.db.ThumbEntry("derived", "th/o1/A3/256", "aXY=", 100)),
                    encDek = "ZGVr",
                    encKeyId = "mk-1",
                ),
                first.data.single(),
            )
        }

    @Test
    fun `a failed facet page is a load error, not a crash`() =
        runTest {
            connectInstance()
            val api = factory.create(host, "eu-west-1", "client-id")
            server.enqueue(MockResponse().setResponseCode(500))
            val result = FacetPagingSource(api, facetUrl(apiBase(), "COUNTRY", "JP")).load(PagingSource.LoadParams.Refresh(null, 60, false))
            assertTrue(result is PagingSource.LoadResult.Error)
        }

    /** What dev actually returned on 2026-11-14: facet items carry no `mime`/`status`
     * (design.md "`F#` facet items"), and requiring them crashed the app. */
    @Test
    fun `a facet entry without mime or status still loads`() =
        runTest {
            connectInstance()
            val api = factory.create(host, "eu-west-1", "client-id")
            val body =
                """{"items":[{"photoId":"A1","facetType":"PLACE","facetValue":"1857910",""" +
                    """"takenAt":"2026-11-12T07:00:00.000Z","thumbs":{},"encDek":"ZGVr","encKeyId":"mk-1",""" +
                    """"width":1200,"height":900,"tzOffsetMin":540}]}"""
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val page =
                FacetPagingSource(api, facetUrl(apiBase(), "PLACE", "1857910"))
                    .load(PagingSource.LoadParams.Refresh(null, 60, false)) as PagingSource.LoadResult.Page
            val entity = page.data.single()
            assertEquals("A1", entity.photoId)
            assertEquals("", entity.mime)
            assertEquals(AssetStatus.READY, entity.status)
        }

    @Test
    fun `an unparseable facet page is a load error, not a crash`() =
        runTest {
            connectInstance()
            val api = factory.create(host, "eu-west-1", "client-id")
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"photoId":"A1"}]}"""))
            val result = FacetPagingSource(api, facetUrl(apiBase(), "PLACE", "1857910")).load(PagingSource.LoadParams.Refresh(null, 60, false))
            assertTrue(result is PagingSource.LoadResult.Error)
        }

    @Test
    fun `facet urls carry the type and the id as one path segment`() {
        assertEquals("https://x/api/facets/REGION/JP.22", facetUrl("https://x/api", "REGION", "JP.22"))
        assertEquals("https://x/api/facets/COUNTRY/FK", facetUrl("https://x/api", "COUNTRY", "FK"))
        assertEquals("https://x/api/facets/CAMERA/a%20b%2Fc", facetUrl("https://x/api", "CAMERA", "a b/c"))
    }
}
