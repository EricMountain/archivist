package fr.enry.archivist.data.repo

import androidx.paging.RemoteMediator.MediatorResult
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.FacetStreamEntity
import fr.enry.archivist.data.local.db.HistogramEntity
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.FacetPageResponse
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Timeline place filter step 2: [PlaceFilterRepository]'s session cache, reuse across
 * selections, cancellation, local jumps and the loaded histogram, over the JVM Room driver
 * and a scripted [FacetPageFetcher]. [FacetMergeMediatorTest] covers the merge over HTTP. */
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class PlaceFilterRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var fetcher: FakeFetcher
    private lateinit var repository: PlaceFilterRepository

    private val townA = FacetStreamSpec("PLACE", "1", "FR", "Town A", "1")
    private val townB = FacetStreamSpec("PLACE", "2", "FR", "Town B", "2")
    private val townC = FacetStreamSpec("PLACE", "3", "FR", "Town C", "3")

    /** Pages per stream key; a page's cursor is `<value>-c<next index>`. */
    private class FakeFetcher : FacetPageFetcher {
        val pages = ConcurrentHashMap<String, List<FacetPageResponse>>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val started: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override suspend fun fetch(
            facetType: String,
            value: String,
            cursor: String?,
            limit: Int,
        ): FacetPageResponse {
            val key = "$facetType#$value"
            calls += "$key?${cursor.orEmpty()}"
            started += key
            try {
                gates[key]?.await()
            } catch (e: CancellationException) {
                cancelled += key
                throw e
            }
            return pages.getValue(key)[cursor?.substringAfterLast("-c")?.toInt() ?: 0]
        }
    }

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fetcher = FakeFetcher()
        repository = PlaceFilterRepository(db, fetcher, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun at(day: Int) = "2026-07-%02dT10:00:00.000Z".format(day)

    private fun entry(
        id: String,
        day: Int,
    ) = FacetEntryDto(
        photoId = id,
        facetType = "PLACE",
        facetValue = "x",
        takenAt = at(day),
        encDek = "ZGVr",
        encKeyId = "mk-1",
        tzOffsetMin = 0,
    )

    /** [photos] (id → day, newest first) as pages of [perPage]. */
    private fun serve(
        spec: FacetStreamSpec,
        vararg photos: Pair<String, Int>,
        perPage: Int = 3,
    ) {
        val chunks = photos.toList().chunked(perPage).ifEmpty { listOf(emptyList()) }
        fetcher.pages[spec.key] =
            chunks.mapIndexed { i, chunk ->
                FacetPageResponse(chunk.map { entry(it.first, it.second) }, if (i + 1 < chunks.size) "${spec.value}-c${i + 1}" else null)
            }
    }

    private fun awaitTrue(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!kotlinx.coroutines.runBlocking { condition() }) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private suspend fun ids(vararg streams: FacetStreamSpec) =
        repository.MergedPageQueries(streams.map { it.key }.toSet()).pageFromStart(1000).map { it.photoId }

    @Test
    fun `reselecting a subset fetches nothing, and a superset fetches only the new streams`() =
        runTest {
            serve(townA, "a5" to 5)
            serve(townB, "b4" to 4)
            serve(townC, "c3" to 3)
            repository.loadFirstPages(listOf(townA, townB))
            assertEquals(setOf("PLACE#1?", "PLACE#2?"), fetcher.calls.toSet())

            repository.loadFirstPages(listOf(townA))
            assertEquals(listOf("a5"), ids(townA))
            assertEquals(2, fetcher.calls.size)

            repository.loadFirstPages(listOf(townA, townB, townC))
            assertEquals(3, fetcher.calls.size)
            assertEquals("PLACE#3?", fetcher.calls.last())
            assertEquals(listOf("a5", "b4", "c3"), ids(townA, townB, townC))
        }

    @Test
    fun `a new selection cancels the fetch of a stream it dropped and lets a kept one finish`() =
        runTest {
            serve(townA, "a5" to 5)
            serve(townB, "b4" to 4)
            fetcher.gates[townA.key] = CompletableDeferred()
            fetcher.gates[townB.key] = CompletableDeferred()

            val old = async(Dispatchers.Default) { repository.loadFirstPages(listOf(townA, townB)) }
            awaitTrue("both first pages requested") { fetcher.started.containsAll(listOf(townA.key, townB.key)) }

            // The next selection keeps A and drops B (what pager(listOf(townA)) does first).
            repository.retain(setOf(townA.key))
            awaitTrue("B cancelled") { townB.key in fetcher.cancelled }
            assertFalse(townA.key in fetcher.cancelled)

            // The new selection's REFRESH joins A's fetch rather than starting another.
            val next = async(Dispatchers.Default) { repository.loadFirstPages(listOf(townA)) }
            fetcher.gates.getValue(townA.key).complete(Unit)
            fetcher.gates.getValue(townB.key).complete(Unit)
            assertTrue(next.await() is MediatorResult.Success)
            assertEquals(1, fetcher.calls.count { it.startsWith(townA.key) })
            assertEquals(listOf("a5"), ids(townA))

            // The old selection's REFRESH saw its B fetch cancelled: an error, not a hang.
            assertTrue(old.await() is MediatorResult.Error)
            assertNull(db.facetStreamDao().stream(townB.key))
            assertEquals(emptyList<Any>(), db.facetStreamDao().rowsOf(townB.key))
        }

    @Test
    fun `clear empties the stream cache and leaves photos, the histogram and the vocabulary alone`() =
        runTest {
            val photo = PhotoEntity("p1", at(1), 0, "image/jpeg", 1, 1, AssetStatus.READY, emptyMap(), "d", "k")
            db.photoDao().upsertAll(listOf(photo))
            db.histogramDao().set(HistogramEntity(days = mapOf("2026-07-01" to 1), total = 1, etag = "\"tl-1\""))
            db.placeVocabularyDao().set("{}", "\"pl-1\"")
            serve(townA, "a5" to 5)
            repository.loadFirstPages(listOf(townA))
            assertEquals(listOf("a5"), ids(townA))

            repository.clear()
            assertNull(db.facetStreamDao().stream(townA.key))
            assertEquals(emptyList<Any>(), db.facetStreamDao().rowsOf(townA.key))
            assertEquals(listOf(photo), db.photoDao().pageFromStart(10))
            assertEquals(1, db.histogramDao().get()?.total)
            assertEquals("\"pl-1\"", db.placeVocabularyDao().get()?.etag)
        }

    @Test
    fun `the first pager of a process starts from an empty cache, later ones keep it`() =
        runTest {
            val dao = db.facetStreamDao()
            dao.upsertStream(FacetStreamEntity("PLACE#old", null, at(1), "x", done = true, fetchedAt = at(1)))
            fetcher.gates[townA.key] = CompletableDeferred() // nothing lands meanwhile
            serve(townA, "a5" to 5)

            repository.pager(listOf(townA)).first()
            awaitTrue("the leftover stream cleared") { dao.stream("PLACE#old") == null }

            dao.upsertStream(FacetStreamEntity("PLACE#this-session", null, at(1), "x", done = true, fetchedAt = at(1)))
            repository.pager(listOf(townA)).first()
            Thread.sleep(100)
            assertTrue(dao.stream("PLACE#this-session") != null)
        }

    @Test
    fun `a local landing is the newest loaded photo on or before the day, else the oldest loaded`() =
        runTest {
            serve(townA, "a10" to 10, "a8" to 8, "a6" to 6, "a4" to 4)
            serve(townB, "b9" to 9, "b7" to 7, "b5" to 5, "b3" to 3)
            repository.loadFirstPages(listOf(townA, townB))
            val both = listOf(townA, townB)
            // Exposed: a10 b9 a8 b7 a6 (b5 is below the watermark a6).
            assertEquals(TimelineKey(at(7), "b7"), repository.localLanding(both, LocalDate.of(2026, 7, 7)))
            assertEquals(TimelineKey(at(10), "a10"), repository.localLanding(both, LocalDate.of(2026, 8, 1)))
            assertEquals(TimelineKey(at(6), "a6"), repository.localLanding(both, LocalDate.of(2026, 7, 5)))
            assertNull(repository.localLanding(both, null))
            // Nothing loaded for an unknown stream: no landing.
            assertNull(repository.localLanding(listOf(townC), LocalDate.of(2026, 7, 7)))
        }

    @Test
    fun `the loaded histogram follows the merged view and says when it is complete`() =
        runTest {
            serve(townA, "a10" to 10, "a10b" to 10, "a6" to 6, "a4" to 4)
            val loadedBefore = repository.loaded(listOf(townA)).first()
            assertEquals(0, loadedBefore.histogram.total)
            assertFalse(loadedBefore.complete)

            repository.loadFirstPages(listOf(townA))
            val first = repository.loaded(listOf(townA)).first()
            assertEquals(mapOf("2026-07-10" to 2, "2026-07-06" to 1), first.histogram.days)
            assertEquals(3, first.histogram.total)
            assertFalse(first.complete)

            repository.loadAtWatermark(listOf(townA))
            val all = repository.loaded(listOf(townA)).first()
            assertEquals(4, all.histogram.total)
            assertTrue(all.complete)
        }

    @Test
    fun `an APPEND keeps fetching while a page adds nothing to the view`() =
        runTest {
            // A's second page is empty but has a cursor (the server's filter ate it).
            fetcher.pages[townA.key] =
                listOf(
                    FacetPageResponse(listOf(entry("a9", 9)), "1-c1"),
                    FacetPageResponse(emptyList(), "1-c2"),
                    FacetPageResponse(listOf(entry("a2", 2)), null),
                )
            repository.loadFirstPages(listOf(townA))
            assertEquals(listOf("a9"), ids(townA))
            assertTrue((repository.loadAtWatermark(listOf(townA)) as MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf("a9", "a2"), ids(townA))
            assertEquals(listOf("PLACE#1?", "PLACE#1?1-c1", "PLACE#1?1-c2"), fetcher.calls.toList())
        }

    @Test
    fun `at most six fetches are in flight at once`() =
        runTest {
            val specs = (1..10).map { FacetStreamSpec("PLACE", "$it", "FR", "T$it", "$it") }
            specs.forEach {
                serve(it, "${it.value}-p" to 1)
                fetcher.gates[it.key] = CompletableDeferred()
            }
            val load = async(Dispatchers.Default) { repository.loadFirstPages(specs) }
            awaitTrue("six started") { fetcher.started.size == MAX_FACET_FETCHES_IN_FLIGHT }
            Thread.sleep(100)
            assertEquals(MAX_FACET_FETCHES_IN_FLIGHT, fetcher.started.size)
            specs.forEach { fetcher.gates.getValue(it.key).complete(Unit) }
            assertTrue(load.await() is MediatorResult.Success)
            assertEquals(10, fetcher.calls.size)
        }

    @Test
    fun `the timeline paging source pages the merged view by key and invalidates on a fetch`() =
        runTest {
            serve(townA, "a10" to 10, "a8" to 8, "a6" to 6, "a4" to 4, "a2" to 2, perPage = 5)
            serve(townB, "b9" to 9, "a8" to 8, "b5" to 5, "b3" to 3, perPage = 2)
            val both = listOf(townA, townB)
            repository.loadFirstPages(both)
            val source =
                TimelinePagingSource(db, repository.MergedPageQueries(both.map { it.key }.toSet()), TimelineJumpCoordinator())
            // B's page is b9, a8: the watermark is a8, and a8 (in both) shows once.
            val first = source.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 2, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals(listOf("a10", "b9"), first.data.map { it.photoId })
            val next = source.load(androidx.paging.PagingSource.LoadParams.Append(first.nextKey!!, 2, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals(listOf("a8"), next.data.map { it.photoId })
            assertNull(next.nextKey)

            assertFalse(source.invalid)
            repository.loadAtWatermark(both)
            awaitTrue("the source invalidated") { source.invalid }
        }

    // Step 7: the detail pager's swipe source and the session flag row patching reads.

    @Test
    fun `loadedPhotos stops at the watermark and grows with loadOlder, like the grid`() =
        runTest {
            serve(townA, "a6" to 6, "a5" to 5, "a4" to 4, "a3" to 3)
            serve(townB, "b2" to 2)
            repository.loadFirstPages(listOf(townA, townB))
            // townA's watermark (a4) cuts b2 off until townA's next page lands.
            assertEquals(listOf("a6", "a5", "a4"), repository.loadedPhotos(listOf(townA, townB)).first().map { it.photoId })
            assertTrue(repository.loadOlder(listOf(townA, townB)))
            assertEquals(listOf("a6", "a5", "a4", "a3", "b2"), repository.loadedPhotos(listOf(townA, townB)).first().map { it.photoId })
        }

    @Test
    fun `the filter session is on from the first pager until a clear`() =
        runTest {
            serve(townA, "a1" to 1)
            assertFalse(repository.filterSessionActive)
            repository.pager(listOf(townA)).first()
            assertTrue(repository.filterSessionActive)
            repository.clear()
            assertFalse(repository.filterSessionActive)
        }

    @Test
    fun `streamContains matches the town, the region or the country`() {
        val kyoto =
            fr.enry.archivist.data.remote.PlaceDto(
                cc = "JP",
                region = fr.enry.archivist.data.remote.PlaceRefDto("JP.22", "Kyoto"),
                locality = fr.enry.archivist.data.remote.PlaceRefDto("1857910", "Kyoto"),
                src = "test",
            )
        assertTrue(streamContains("PLACE#1857910", kyoto))
        assertTrue(streamContains("REGION#JP.22", kyoto))
        assertTrue(streamContains("COUNTRY#JP", kyoto))
        assertFalse(streamContains("PLACE#1853909", kyoto))
        assertFalse(streamContains("REGION#JP.27", kyoto))
        assertFalse(streamContains("COUNTRY#FR", kyoto))
    }
}
