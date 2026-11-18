package fr.enry.archivist.ui.detail

import android.content.Context
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.FacetPageResponse
import fr.enry.archivist.data.repo.DeleteRepository
import fr.enry.archivist.data.repo.FacetPageFetcher
import fr.enry.archivist.data.repo.FacetStreamSpec
import fr.enry.archivist.data.repo.LocationUpdateRepository
import fr.enry.archivist.data.repo.PhotoDetailRepository
import fr.enry.archivist.data.repo.PhotoRepository
import fr.enry.archivist.data.repo.PlaceFilterRepository
import fr.enry.archivist.data.repo.RepairRepository
import fr.enry.archivist.data.repo.RotateRepository
import fr.enry.archivist.data.repo.TakenAtRepository
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Place filter step 7: [DetailViewModel]'s swipe source. Opened from the filtered grid, the
 * pager swipes over the filter's loaded photos only (never the timeline's), and reaching
 * the last one appends a page, on demand. A real [PlaceFilterRepository] on the JVM Room
 * driver with a scripted [FacetPageFetcher]; everything else is a mock.
 */
@OptIn(ExperimentalCoroutinesApi::class, androidx.paging.ExperimentalPagingApi::class)
class DetailViewModelSwipeTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var placeFilter: PlaceFilterRepository
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val kyoto = FacetStreamSpec("PLACE", "1857910", "JP", "Kyoto", "1857910")
    private val osaka = FacetStreamSpec("PLACE", "1853909", "JP", "Osaka", "1853909")

    /** Kyoto: k6..k1 in pages of 3 (cursor "c1" for the second); Osaka: o9 alone. */
    private val fetcher =
        FacetPageFetcher { type, value, cursor, _ ->
            calls += "$type#$value?${cursor.orEmpty()}"
            when (value) {
                kyoto.value ->
                    if (cursor == null) {
                        FacetPageResponse(listOf(entry("k6", 26), entry("k5", 25), entry("k4", 24)), cursor = "c1")
                    } else {
                        FacetPageResponse(listOf(entry("k3", 23), entry("k2", 22), entry("k1", 21)), cursor = null)
                    }
                else -> FacetPageResponse(listOf(entry("o9", 29)), cursor = null)
            }
        }

    private fun entry(
        id: String,
        day: Int,
    ) = FacetEntryDto(
        photoId = id,
        facetType = "PLACE",
        facetValue = "x",
        takenAt = "2026-07-%02dT10:00:00.000Z".format(day),
        encDek = "ZGVr",
        encKeyId = "mk-1",
    )

    private val timelineOnly =
        PhotoEntity(
            photoId = "t1",
            takenAt = "2026-07-30T10:00:00.000Z",
            tzOffsetMin = 0,
            mime = "image/jpeg",
            width = 1,
            height = 1,
            status = AssetStatus.READY,
            thumbs = emptyMap(),
            encDek = "dek",
            encKeyId = "mk-1",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = buildTestDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        placeFilter = PlaceFilterRepository(db, fetcher, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): DetailViewModel {
        val photoRepository = mock<PhotoRepository>().also { whenever(it.observeTimeline()).thenReturn(flowOf(listOf(timelineOnly))) }
        val instanceStore = mock<InstanceStore>().also { whenever(it.current).thenReturn(flowOf(null)) }
        return DetailViewModel(
            photoRepository = photoRepository,
            photoDetailRepository = mock<PhotoDetailRepository>(),
            deleteRepository = mock<DeleteRepository>(),
            repairRepository = mock<RepairRepository>(),
            takenAtRepository = mock<TakenAtRepository>(),
            rotateRepository = mock<RotateRepository>(),
            locationUpdateRepository = mock<LocationUpdateRepository>(),
            placeFilterRepository = placeFilter,
            instanceStore = instanceStore,
            context = mock<Context>(),
        )
    }

    private fun ids(list: List<PhotoEntity>) = list.map { it.photoId }

    @Test
    fun `the filter source swipes only within the filter, and appends at its end`() =
        runBlocking {
            val vm = viewModel()
            val source = DetailSwipeSource.Filter(listOf(kyoto))
            placeFilter.loadFirstPages(source.streams)

            val first = withTimeout(5_000) { vm.swipePhotos(source).first { it.isNotEmpty() } }
            // The loaded Kyoto page only: not the timeline's photo, not Osaka's.
            assertEquals(listOf("k6", "k5", "k4"), ids(first))

            // On the last loaded photo: one more page, the next of the stream holding the
            // watermark, and the list grows with it.
            vm.onReachedEnd(source)
            val grown = withTimeout(5_000) { vm.swipePhotos(source).first { it.size > 3 } }
            assertEquals(listOf("k6", "k5", "k4", "k3", "k2", "k1"), ids(grown))
            assertEquals(listOf("PLACE#1857910?", "PLACE#1857910?c1"), calls.toList())

            // The stream is done: reaching the end again fetches nothing.
            vm.onReachedEnd(source)
            Thread.sleep(200)
            assertEquals(2, calls.size)
        }

    @Test
    fun `two places merge newest first, and the timeline source is the timeline`() =
        runBlocking {
            val vm = viewModel()
            val source = DetailSwipeSource.Filter(listOf(kyoto, osaka))
            placeFilter.loadFirstPages(source.streams)

            // Osaka is done; Kyoto's watermark (k4) cuts the view: nothing older is exposed
            // until its next page lands, exactly as in the grid.
            val merged = withTimeout(5_000) { vm.swipePhotos(source).first { it.isNotEmpty() } }
            assertEquals(listOf("o9", "k6", "k5", "k4"), ids(merged))

            assertEquals(listOf("t1"), ids(withTimeout(5_000) { vm.swipePhotos(DetailSwipeSource.Timeline).first { it.isNotEmpty() } }))
            // The timeline source has nothing to append.
            vm.onReachedEnd(DetailSwipeSource.Timeline)
            assertEquals(2, calls.size)
        }
}
