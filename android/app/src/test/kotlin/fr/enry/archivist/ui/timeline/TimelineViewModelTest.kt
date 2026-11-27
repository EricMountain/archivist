package fr.enry.archivist.ui.timeline

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.FacetStreamEntity
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.FacetPageResponse
import fr.enry.archivist.data.remote.PlaceCountryDto
import fr.enry.archivist.data.remote.PlaceLocalityDto
import fr.enry.archivist.data.remote.PlaceRegionDto
import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.data.repo.FacetStreamSpec
import fr.enry.archivist.data.repo.JumpOutcome
import fr.enry.archivist.data.repo.LoadedRows
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.data.repo.PhotoRepository
import fr.enry.archivist.data.repo.PlaceFilterRepository
import fr.enry.archivist.data.repo.TimelineHistogram
import fr.enry.archivist.data.repo.UploadEvents
import fr.enry.archivist.ui.search.SelectionState
import fr.enry.archivist.ui.search.selectionTreeFor
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private fun photo(
    id: String,
    takenAt: String,
    tzOffsetMin: Int = 0,
) = PhotoEntity(
    photoId = id,
    takenAt = takenAt,
    tzOffsetMin = tzOffsetMin,
    mime = "image/jpeg",
    width = 10,
    height = 10,
    status = AssetStatus.READY,
    thumbs = emptyMap(),
    encDek = "dek",
    encKeyId = "mk-1",
)

class TimelineViewModelTest {
    @Test
    fun `localDate uses the offset, not UTC`() {
        // 2024-01-01T23:30:00Z at UTC+2 is 2024-01-02T01:30 local.
        assertEquals(LocalDate.of(2024, 1, 2), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 120).localDate())
        assertEquals(LocalDate.of(2024, 1, 1), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 0).localDate())
    }
}

/**
 * Timeline place filter step 3: [TimelineViewModel]'s filter mode, in virtual time. The
 * repositories are mocks except in the last test, which runs a real
 * [PlaceFilterRepository] over the JVM Room driver to show `photos` is never touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var photoRepository: PhotoRepository
    private lateinit var filterRepository: PlaceFilterRepository

    /** Pager builds (calls to `pager`), collections started, and collections cancelled. */
    private val built = mutableListOf<List<String>>()
    private var started = 0
    private var cancelled = 0

    private val vocabulary =
        PlacesResponse(
            countries = listOf(PlaceCountryDto("FR", 20), PlaceCountryDto("JP", 9)),
            regions = listOf(PlaceRegionDto("JP.22", "Kyoto", "JP", 4)),
            localities =
                listOf(
                    PlaceLocalityDto("2988507", "Paris", null, "FR", 12),
                    PlaceLocalityDto("2988358", "Pau", null, "FR", 8),
                    PlaceLocalityDto("1857910", "Kyoto", "Kyoto", "JP", 4),
                    PlaceLocalityDto("1853909", "Osaka", null, "JP", 5),
                ),
        )

    private fun state(query: String) = SelectionState.initial(query, selectionTreeFor(vocabulary, query, Locale.ENGLISH))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        photoRepository =
            mock<PhotoRepository>().also {
                whenever(it.histogram()).thenReturn(flowOf(null))
                whenever(it.timeline(anyOrNull())).thenReturn(flowOf(PagingData.empty()))
                it.stub {
                    onBlocking { hasCachedPhotos() } doReturn false
                    onBlocking { cacheStartsInPast() } doReturn false
                    onBlocking { fetchTimelineBounds() } doReturn null
                }
            }
        filterRepository =
            mock<PlaceFilterRepository>().also {
                whenever(it.pager(any(), anyOrNull())).thenAnswer { inv ->
                    @Suppress("UNCHECKED_CAST")
                    built += (inv.arguments[0] as List<FacetStreamSpec>).map { s -> s.key }
                    flow<PagingData<PhotoEntity>> {
                        started++
                        try {
                            emit(PagingData.empty())
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                }
                whenever(it.loaded(any())).thenReturn(flowOf(LoadedRows(TimelineHistogram(mapOf("2026-07-01" to 3), 3), complete = false)))
            }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(filterRepo: PlaceFilterRepository = filterRepository): TimelineViewModel {
        val keys = MasterKeyHolder().also { it.set(MasterKey.of(ByteArray(32) { 1 })) }
        val instanceStore = mock<InstanceStore>().also { whenever(it.current).thenReturn(flowOf(null)) }
        return TimelineViewModel(photoRepository, filterRepo, keys, instanceStore, UploadEvents())
    }

    /** Collects both grids' flows, as the screen does. */
    private fun TestScope.collectGrids(vm: TimelineViewModel) {
        backgroundScope.launch { vm.timeline.collect {} }
        backgroundScope.launch { vm.filteredTimeline.collect {} }
        backgroundScope.launch { vm.filteredRail.collect {} }
    }

    @Test
    fun `three keystrokes within the debounce build one pager`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            runCurrent()
            vm.onSelectionChanged(state("pa"))
            advanceTimeBy(100)
            vm.onSelectionChanged(state("par"))
            advanceTimeBy(100)
            vm.onSelectionChanged(state("pari"))
            advanceTimeBy(FILTER_DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(0, built.size)
            assertNull(vm.activeFilter.value)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(listOf("PLACE#2988507")), built)
            assertEquals("pari", vm.activeFilter.value?.query)
            assertEquals(1, started)
        }

    @Test
    fun `one character doesn't apply, two do`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("p"))
            advanceTimeBy(5_000)
            runCurrent()
            assertNull(vm.activeFilter.value)
            assertEquals(0, built.size)

            vm.onSelectionChanged(state("pa"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            // "pa" is Paris, Pau, and Japan (mid-word).
            assertEquals(setOf("PLACE#2988507", "PLACE#2988358", "COUNTRY#JP"), vm.activeFilter.value?.streamKeys)
            assertEquals(1, built.size)
            // A tick applies under the floor too.
            vm.onSelectionChanged(state("p").toggle("PLACE#2988358"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(2, built.size)
        }

    @Test
    fun `applyNow applies at once, even for one character, and the debounce adds nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("o"))
            vm.applyNow()
            runCurrent()
            assertEquals("o", vm.activeFilter.value?.query)
            assertEquals(1, built.size)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, built.size)
        }

    @Test
    fun `a superseded selection's pager is cancelled, and the same streams don't rebuild`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("kyoto"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(1, started)
            assertEquals(0, cancelled)

            vm.onSelectionChanged(state("osaka"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(listOf(listOf("REGION#JP.22"), listOf("PLACE#1853909")), built)
            assertEquals(2, started)
            assertEquals(1, cancelled)

            // "osak" selects the same stream: the query changes, the pager doesn't.
            vm.onSelectionChanged(state("osak"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals("osak", vm.activeFilter.value?.query)
            assertEquals(2, built.size)
        }

    @Test
    fun `the unfiltered pager and the photos cache are never asked for anything in filter mode`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            advanceUntilIdle()
            verify(photoRepository, times(1)).timeline(anyOrNull())

            vm.onSelectionChanged(state("kyoto"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            filterRepository.stub { onBlocking { localLanding(any(), anyOrNull()) } doReturn TimelineKey("2026-07-01T00:00:00.000Z", "k1") }
            vm.onScrubTo(LocalDate.of(2026, 7, 1))
            vm.onJumpCommitted(RailPosition(LocalDate.of(2026, 7, 1)))
            vm.clearFilter()
            advanceUntilIdle()

            verify(photoRepository, times(1)).timeline(anyOrNull())
            verify(photoRepository, never()).jumpTo(anyOrNull(), any())
            verify(photoRepository, never()).refreshLatest()
        }

    @Test
    fun `a filtered jump lands in Room, rebuilds the filtered pager there, and is tagged filtered`() =
        runTest(dispatcher) {
            val landing = TimelineKey("2026-07-01T00:00:00.000Z", "k1")
            filterRepository.stub { onBlocking { localLanding(any(), anyOrNull()) } doReturn landing }
            val vm = viewModel()
            collectGrids(vm)
            val jumps = mutableListOf<JumpLanding>()
            backgroundScope.launch { vm.jumpCompleted.collect { jumps += it } }
            vm.onSelectionChanged(state("kyoto"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()

            vm.onJumpCommitted(RailPosition(LocalDate.of(2026, 7, 1)))
            advanceUntilIdle()
            assertEquals(listOf(TimelineMode.FILTERED), jumps.map { it.mode })
            assertEquals(landing, jumps.single().landing)
            verify(filterRepository).pager(any(), org.mockito.kotlin.eq(landing))
            verify(photoRepository, never()).jumpTo(anyOrNull(), any())

            // The same landing again doesn't rebuild.
            vm.onScrubTo(LocalDate.of(2026, 7, 1))
            advanceUntilIdle()
            assertEquals(2, built.size)
            assertEquals(2, jumps.size)
        }

    @Test
    fun `an unfiltered jump is tagged unfiltered`() =
        runTest(dispatcher) {
            val landing = TimelineKey("2026-07-01T00:00:00.000Z", "p1")
            photoRepository.stub { onBlocking { jumpTo(anyOrNull(), any()) } doReturn JumpOutcome(landing) }
            val vm = viewModel()
            collectGrids(vm)
            val jumps = mutableListOf<JumpLanding>()
            backgroundScope.launch { vm.jumpCompleted.collect { jumps += it } }
            runCurrent()
            vm.onJumpCommitted(RailPosition(LocalDate.of(2026, 7, 1)))
            advanceUntilIdle()
            assertEquals(listOf(TimelineMode.UNFILTERED), jumps.map { it.mode })
            verify(filterRepository, never()).localLanding(any(), anyOrNull())
        }

    @Test
    fun `clear leaves no filter, cancels the filtered pager and empties the cache`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("paris"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertNotNull(vm.activeFilter.value)
            assertNotNull(vm.filteredRail.value)

            vm.clearFilter()
            advanceUntilIdle()
            assertNull(vm.activeFilter.value)
            assertNull(vm.filteredRail.value)
            assertEquals(1, cancelled)
            verify(filterRepository).beginClear()
        }

    @Test
    fun `a query matching nothing is an active, empty filter, not the unfiltered timeline`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("zzz"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            val filter = vm.activeFilter.value
            assertNotNull(filter)
            assertTrue(filter!!.isEmptySelection)
            assertEquals(0, built.size, "nothing to page")
            assertEquals(0, vm.filteredRail.value?.total)
        }

    @Test
    fun `the filtered rail's N is the vocabulary's count until everything is loaded`() =
        runTest(dispatcher) {
            val vm = viewModel()
            collectGrids(vm)
            vm.onSelectionChanged(state("paris"))
            advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(FilteredRail(12, TimelineHistogram(mapOf("2026-07-01" to 3), 3), complete = false), vm.filteredRail.value)
        }

    @Test
    fun `with a real filter repository, the photos table is untouched and clear empties the cache`() =
        runTest(dispatcher) {
            val db: AppDatabase = buildTestDatabase()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val photo = PhotoEntity("p1", "2026-07-01T10:00:00.000Z", 0, "image/jpeg", 1, 1, AssetStatus.READY, emptyMap(), "d", "k")
                db.photoDao().upsertAll(listOf(photo))
                val fetcher =
                    fr.enry.archivist.data.repo.FacetPageFetcher { _, value, _, _ ->
                        FacetPageResponse(
                            listOf(FacetEntryDto("f-$value", "PLACE", value, "2026-07-02T10:00:00.000Z", encDek = "d", encKeyId = "k")),
                            null,
                        )
                    }
                val vm = viewModel(PlaceFilterRepository(db, fetcher, scope))
                collectGrids(vm)
                vm.onSelectionChanged(state("kyoto"))
                advanceTimeBy(FILTER_DEBOUNCE_MS + 1)
                runCurrent()
                // PagingData loads only when something presents it.
                backgroundScope.launch { vm.filteredTimeline.asSnapshot() }
                awaitStreams(db, "REGION#JP.22") { it.isNotEmpty() }
                assertEquals(listOf(photo), db.photoDao().pageFromStart(10))

                vm.clearFilter()
                awaitStreams(db, "REGION#JP.22") { it.isEmpty() }
                assertEquals(listOf(photo), db.photoDao().pageFromStart(10))
                assertNull(vm.activeFilter.value)
            } finally {
                scope.cancel()
                db.close()
            }
        }

    /**
     * The step 3 race: `clearFilter()` used to empty the cache in a coroutine of its own,
     * so a filter applied right after it raced the wipe. Here the old session's fetch is
     * stuck mid-write (non-cancellable, as a Room write is) when the filter is cleared and
     * the same place is applied again at once. The new selection's first page must land
     * after the wipe, not be deleted by it, and the old session's late page must not
     * survive it either.
     */
    @Test
    fun `a filter applied right after a clear loads, and the pending clear neither wipes nor cancels it`() =
        runTest(dispatcher) {
            val db: AppDatabase = buildTestDatabase()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val gate = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val firstFetchStarted = CompletableDeferred<Unit>()
                val fetcher =
                    fr.enry.archivist.data.repo.FacetPageFetcher { _, value, _, _ ->
                        val n = calls.incrementAndGet()
                        if (n == 1) {
                            firstFetchStarted.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                        }
                        FacetPageResponse(
                            listOf(FacetEntryDto("p$n", "REGION", value, "2026-07-0${n}T10:00:00.000Z", encDek = "d", encKeyId = "k")),
                            null,
                        )
                    }
                val repo = PlaceFilterRepository(db, fetcher, scope)
                val vm = viewModel(repo)
                collectGrids(vm)
                val errors = mutableListOf<Throwable>()
                vm.onSelectionChanged(state("kyoto"))
                vm.applyNow()
                runCurrent()
                // After the filter: a snapshot taken before it settles on the empty pages.
                backgroundScope.launch {
                    try {
                        vm.filteredTimeline.asSnapshot()
                    } catch (e: Throwable) {
                        if (e !is kotlinx.coroutines.CancellationException) errors += e
                    }
                }
                firstFetchStarted.await()

                vm.clearFilter()
                vm.onSelectionChanged(state("kyoto"))
                vm.applyNow()
                backgroundScope.launch {
                    try {
                        vm.filteredTimeline.asSnapshot()
                    } catch (e: Throwable) {
                        if (e !is kotlinx.coroutines.CancellationException) errors += e
                    }
                }
                // Give the re-applied filter every chance to run ahead of the stuck clear.
                holdReal(300)
                gate.complete(Unit)

                val key = "REGION#JP.22"
                awaitStreams(db, key) { it.isNotEmpty() }
                // ...and a late wipe every chance to delete it.
                holdReal(200)
                assertNotNull(db.facetStreamDao().stream(key), "the pending clear wiped the new selection's first page")
                assertEquals(listOf("p2"), repo.MergedPageQueries(setOf(key)).pageFromStart(10).map { it.photoId })
                assertEquals(emptyList<Throwable>(), errors)
            } finally {
                scope.cancel()
                db.close()
            }
        }

    /**
     * Suspends until the `facet_streams` rows for [key] satisfy [condition]. Room's flow
     * re-emits on every write, so this waits for the write itself rather than polling, and
     * runTest keeps running the test dispatcher meanwhile.
     *
     * These waits used to busy-poll (`advanceUntilIdle()` + `Thread.sleep`) without ever
     * suspending the test body, which starved work that only runs once the body yields its
     * thread. When the pager's start was held up behind the first session's cache wipe (a
     * real IO thread), the filtered pager's PagingData never reached `cachedIn`'s
     * subscribers, nothing presented it, no fetch started, and the poll timed out — about
     * one run in ten on CI and locally.
     */
    private suspend fun awaitStreams(
        db: AppDatabase,
        key: String,
        condition: (List<FacetStreamEntity>) -> Boolean,
    ) {
        db.facetStreamDao().observeStreams(setOf(key)).first(condition)
    }

    /**
     * A real-time window for a racing real-thread party (a stuck clear, a late wipe) to
     * misbehave in. Inherently real-time: the correct outcome is that nothing happens, so
     * there is no event to await. Suspends rather than sleeping the test thread, so runTest
     * keeps running the test dispatcher (and advancing virtual time) throughout.
     */
    private suspend fun holdReal(ms: Long) {
        withContext(Dispatchers.Default) { delay(ms) }
    }
}
