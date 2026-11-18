package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator.MediatorResult
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Timeline place filter step 2: [FacetMergeMediator] over the real stream cache (JVM Room
 * driver) and `GET /facets` on a MockWebServer whose pages are three items long whatever
 * `limit` says, so a few photos make several pages.
 */
@OptIn(ExperimentalPagingApi::class)
class FacetMergeMediatorTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: PlaceFilterRepository

    private val json = Json { ignoreUnknownKeys = true }

    /** `"<type>/<value>?<cursor or empty>"` → response body (or a status code). */
    private val pages = ConcurrentHashMap<String, String>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val townA = FacetStreamSpec("PLACE", "1", "FR", "Town A", "1")
    private val townB = FacetStreamSpec("PLACE", "2", "FR", "Town B", "2")
    private val region = FacetStreamSpec("REGION", "FR.11", "FR", "Region", "FR.11")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val segs = url.pathSegments
                    val key = "${segs[segs.size - 2]}/${segs.last()}?${url.queryParameter("cursor").orEmpty()}"
                    requests += key
                    failures[key]?.let { code ->
                        failures.remove(key)
                        return MockResponse().setResponseCode(code)
                    }
                    return pages[key]?.let { MockResponse().setResponseCode(200).setBody(it) } ?: MockResponse().setResponseCode(404)
                }
            }
        server.start()
        tempDir = Files.createTempDirectory("facet-merge-test").toFile()
        val instanceStore = InstanceStore(PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "i.preferences_pb") }), json)
        kotlinx.coroutines.runBlocking {
            instanceStore.save(
                "photos.example.com",
                DiscoveryDocument(
                    apiBase = server.url("/api").toString().trimEnd('/'),
                    region = "eu-west-1",
                    cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                    cryptoVersion = 1,
                    instanceName = "Home photos",
                ),
            )
        }
        val factory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        db = buildTestDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = PlaceFilterRepository(db, ApiFacetPageFetcher(instanceStore, factory), scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        tempDir.deleteRecursively()
        db.close()
    }

    /** Day [day] of July 2026, at 10:00 UTC. */
    private fun at(day: Int) = "2026-07-%02dT10:00:00.000Z".format(day)

    private fun entry(
        photoId: String,
        day: Int,
    ) = """{"photoId":"$photoId","facetType":"X","facetValue":"x","takenAt":"${at(day)}","thumbs":{},""" +
        """"encDek":"ZGVr","encKeyId":"mk-1","width":10,"height":10,"tzOffsetMin":0}"""

    /** Serves [spec]'s photos (photoId → day, newest first) three to a page. */
    private fun serve(
        spec: FacetStreamSpec,
        vararg photos: Pair<String, Int>,
    ) {
        val chunks = photos.toList().chunked(3)
        if (chunks.isEmpty()) {
            pages["${spec.facetType}/${spec.value}?"] = """{"items":[]}"""
            return
        }
        chunks.forEachIndexed { i, chunk ->
            val cursor = if (i == 0) "" else "${spec.value}-c$i"
            val next = if (i + 1 < chunks.size) ""","cursor":"${spec.value}-c${i + 1}"""" else ""
            pages["${spec.facetType}/${spec.value}?$cursor"] = """{"items":[${chunk.joinToString(",") { entry(it.first, it.second) }}]$next}"""
        }
    }

    private val emptyState = PagingState<TimelineKey, PhotoEntity>(emptyList(), null, PagingConfig(60), 0)

    private fun mediator(vararg streams: FacetStreamSpec) = FacetMergeMediator(streams.toList(), repository)

    private suspend fun merged(vararg streams: FacetStreamSpec): List<PhotoEntity> =
        repository.MergedPageQueries(streams.map { it.key }.toSet()).pageFromStart(1000)

    private suspend fun ids(vararg streams: FacetStreamSpec) = merged(*streams).map { it.photoId }

    @Test
    fun `two interleaved streams merge newest first, exposing nothing a later page could precede`() =
        runTest {
            serve(townA, "a10" to 10, "a8" to 8, "a6" to 6, "a4" to 4, "a2" to 2)
            serve(townB, "b9" to 9, "b7" to 7, "b5" to 5, "b3" to 3, "b1" to 1)
            val m = mediator(townA, townB)

            val refresh = m.load(LoadType.REFRESH, emptyState)
            assertFalse((refresh as MediatorResult.Success).endOfPaginationReached)
            // A has a10 a8 a6, B b9 b7 b5: the watermark is the newer of a6 and b5. a6 is
            // shown; nothing older is, since B's next page could hold a photo before it.
            assertEquals(listOf("a10", "b9", "a8", "b7", "a6"), ids(townA, townB))

            // The watermark is A's (a6 is newer than b5): APPEND fetches A's next page only.
            requests.clear()
            assertFalse((m.load(LoadType.APPEND, emptyState) as MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf("PLACE/1?1-c1"), requests.toList())
            // A is done (a4 a2), so B's b5 bounds it now.
            assertEquals(listOf("a10", "b9", "a8", "b7", "a6", "b5"), ids(townA, townB))

            requests.clear()
            assertTrue((m.load(LoadType.APPEND, emptyState) as MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf("PLACE/2?2-c1"), requests.toList())
            assertEquals(listOf("a10", "b9", "a8", "b7", "a6", "b5", "a4", "b3", "a2", "b1"), ids(townA, townB))

            // PREPEND has nothing to do and asks for nothing.
            requests.clear()
            assertTrue((m.load(LoadType.PREPEND, emptyState) as MediatorResult.Success).endOfPaginationReached)
            assertEquals(emptyList<String>(), requests.toList())
        }

    @Test
    fun `REFRESH fetches only first pages, and nothing more happens without an APPEND`() =
        runTest {
            serve(townA, "a10" to 10, "a8" to 8, "a6" to 6, "a4" to 4)
            serve(townB, "b9" to 9, "b7" to 7, "b5" to 5, "b3" to 3)
            mediator(townA, townB).load(LoadType.REFRESH, emptyState)
            assertEquals(setOf("PLACE/1?", "PLACE/2?"), requests.toSet())
            Thread.sleep(200)
            assertEquals(2, server.requestCount)
            // A second REFRESH (a rebuilt pager) has everything it needs already.
            mediator(townA, townB).load(LoadType.REFRESH, emptyState)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a photo in two streams appears once, with the more specific place`() =
        runTest {
            // An unlinked town (F5) ticked with its region: both hold "x".
            serve(region, "r9" to 9, "x" to 8)
            serve(townA, "x" to 8)
            mediator(region, townA).load(LoadType.REFRESH, emptyState)
            val rows = merged(region, townA)
            assertEquals(listOf("r9", "x"), rows.map { it.photoId })
            assertEquals("Town A", rows[1].placeName)
            assertEquals("1", rows[1].placeKey)
            assertEquals("Region", rows[0].placeName)
            assertEquals("FR.11", rows[0].placeKey)
            assertEquals("FR", rows[0].placeCc)
            assertEquals(null, rows[0].preview)
        }

    @Test
    fun `a country stream's rows carry no place name`() =
        runTest {
            val country = FacetStreamSpec("COUNTRY", "JP", "JP", null, null)
            serve(country, "j1" to 3)
            mediator(country).load(LoadType.REFRESH, emptyState)
            val row = merged(country).single()
            assertEquals("JP", row.placeCc)
            assertEquals(null, row.placeName)
            assertEquals(null, row.placeKey)
        }

    @Test
    fun `empty streams give an empty result and end of pagination`() =
        runTest {
            serve(townA)
            serve(townB)
            val refresh = mediator(townA, townB).load(LoadType.REFRESH, emptyState) as MediatorResult.Success
            assertTrue(refresh.endOfPaginationReached)
            assertEquals(emptyList<String>(), ids(townA, townB))
        }

    @Test
    fun `a 500 is an error, and a retry resumes from the stored cursor`() =
        runTest {
            serve(townA, "a10" to 10, "a8" to 8, "a6" to 6, "a4" to 4)
            val m = mediator(townA)
            m.load(LoadType.REFRESH, emptyState)
            failures["PLACE/1?1-c1"] = 500

            assertTrue(m.load(LoadType.APPEND, emptyState) is MediatorResult.Error)
            assertEquals(listOf("a10", "a8", "a6"), ids(townA))

            requests.clear()
            assertTrue((m.load(LoadType.APPEND, emptyState) as MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf("PLACE/1?1-c1"), requests.toList())
            assertEquals(listOf("a10", "a8", "a6", "a4"), ids(townA))
        }

    @Test
    fun `a first-page failure is a REFRESH error and blocks the merged view`() =
        runTest {
            serve(townA, "a10" to 10)
            failures["PLACE/2?"] = 503
            serve(townB, "b9" to 9)
            assertTrue(mediator(townA, townB).load(LoadType.REFRESH, emptyState) is MediatorResult.Error)
            // B has no page: nothing shows, not even A's rows.
            assertEquals(emptyList<String>(), ids(townA, townB))
            // A's fetch carries on in the repository's scope (only B failed); once it has
            // landed, a retry fetches B alone.
            val deadline = System.currentTimeMillis() + 5_000
            while (db.facetStreamDao().stream(townA.key) == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            requests.clear()
            mediator(townA, townB).load(LoadType.REFRESH, emptyState)
            assertEquals(listOf("PLACE/2?"), requests.toList())
            assertEquals(listOf("a10", "b9"), ids(townA, townB))
        }
}
