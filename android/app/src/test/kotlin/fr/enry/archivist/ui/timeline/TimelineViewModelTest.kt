package fr.enry.archivist.ui.timeline

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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

/** Every item in [items] in one page, in whatever order the caller already sorted them
 * (matching how [fr.enry.archivist.data.local.db.PhotoDao.pagingSource] itself orders
 * rows) — enough to exercise [toTimelineItems] without a real Room DB. */
private class FixedPagingSource(private val items: List<PhotoEntity>) : PagingSource<Int, PhotoEntity>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotoEntity> =
        LoadResult.Page(data = items, prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, PhotoEntity>): Int? = null
}

class TimelineViewModelTest {
    private suspend fun snapshotOf(items: List<PhotoEntity>): List<TimelineItem> =
        Pager(PagingConfig(pageSize = maxOf(items.size, 1), enablePlaceholders = false)) { FixedPagingSource(items) }
            .flow
            .toTimelineItems()
            .asSnapshot()

    @Test
    fun `localDate uses the offset, not UTC`() {
        // 2024-01-01T23:30:00Z at UTC+2 is 2024-01-02T01:30 local.
        assertEquals(LocalDate.of(2024, 1, 2), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 120).localDate())
        assertEquals(LocalDate.of(2024, 1, 1), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 0).localDate())
    }

    @Test
    fun `a single header is inserted ahead of two photos on the same local day`() =
        runTest {
            val snapshot =
                snapshotOf(
                    listOf(
                        photo("p2", "2024-01-01T10:00:00.000Z"),
                        photo("p1", "2024-01-01T09:00:00.000Z"),
                    ),
                )

            assertEquals(
                listOf(
                    TimelineItem.Header(LocalDate.of(2024, 1, 1), anchorPhotoId = "p2"),
                    TimelineItem.Photo(photo("p2", "2024-01-01T10:00:00.000Z")),
                    TimelineItem.Photo(photo("p1", "2024-01-01T09:00:00.000Z")),
                ),
                snapshot,
            )
        }

    @Test
    fun `a new header appears when the local day changes`() =
        runTest {
            val snapshot =
                snapshotOf(
                    listOf(
                        photo("p2", "2024-01-02T10:00:00.000Z"),
                        photo("p1", "2024-01-01T09:00:00.000Z"),
                    ),
                )

            assertEquals(
                listOf(
                    TimelineItem.Header(LocalDate.of(2024, 1, 2), anchorPhotoId = "p2"),
                    TimelineItem.Photo(photo("p2", "2024-01-02T10:00:00.000Z")),
                    TimelineItem.Header(LocalDate.of(2024, 1, 1), anchorPhotoId = "p1"),
                    TimelineItem.Photo(photo("p1", "2024-01-01T09:00:00.000Z")),
                ),
                snapshot,
            )
        }

    @Test
    fun `photos on different UTC dates group under the same header once offsets are applied`() =
        runTest {
            // 2024-01-01T23:30Z at UTC+2 -> 2024-01-02 local; 2024-01-02T00:10Z at UTC -> 2024-01-02 local too.
            val snapshot =
                snapshotOf(
                    listOf(
                        photo("late-utc", "2024-01-02T00:10:00.000Z", tzOffsetMin = 0),
                        photo("crosses-midnight", "2024-01-01T23:30:00.000Z", tzOffsetMin = 120),
                    ),
                )

            val headers = snapshot.filterIsInstance<TimelineItem.Header>()
            assertEquals(listOf(LocalDate.of(2024, 1, 2)), headers.map { it.date })
        }

    /** Regression test for a real crash: a 1,000-photo live run against the `dev`
     * instance hit `IllegalArgumentException: Key "header-2026-08-22" was already
     * used` the first time two headers for the same calendar date landed
     * non-adjacently in one loaded window. The UTC sort order and the *local-day*
     * grouping aren't monotonic with each other once photos carry different
     * `tzOffsetMin` values -- a photo with a large positive offset can "jump" its
     * local date ahead of an earlier-UTC photo that's still on the previous local
     * day, so the same date recurs later in the list instead of every occurrence
     * being contiguous. */
    @Test
    fun `a repeated local date produces two headers with different anchors, not a duplicate key`() =
        runTest {
            val a = photo("a", "2024-01-03T20:00:00.000Z", tzOffsetMin = 0) // local 2024-01-03
            val b = photo("b", "2024-01-03T10:00:00.000Z", tzOffsetMin = -660) // local 2024-01-02
            val c = photo("c", "2024-01-02T23:00:00.000Z", tzOffsetMin = 600) // local 2024-01-03 again

            val snapshot = snapshotOf(listOf(a, b, c))

            val headers = snapshot.filterIsInstance<TimelineItem.Header>()
            assertEquals(listOf(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)), headers.map { it.date })
            // The whole point: two headers share a date, but their keys (date +
            // anchorPhotoId, exactly what TimelineScreen.kt's grid uses) must not collide.
            val keys = headers.map { "header-${it.anchorPhotoId}" }
            assertEquals(keys.size, keys.toSet().size)
        }
}
