package fr.enry.archivist.data.repo

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.buildTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TimelinePagingSourceTest {
    private lateinit var db: AppDatabase
    private lateinit var jumpCoordinator: TimelineJumpCoordinator
    private lateinit var source: TimelinePagingSource

    private val config = PagingConfig(pageSize = 2, enablePlaceholders = false)

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        jumpCoordinator = TimelineJumpCoordinator()
        source = TimelinePagingSource(db, db.photoDao(), jumpCoordinator)
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun photo(
        photoId: String,
        takenAt: String,
    ) = PhotoEntity(photoId, takenAt, 0, "image/jpeg", 1, 1, AssetStatus.READY, emptyMap(), "dek", "mk-1")

    private suspend fun seed(vararg photos: PhotoEntity) = db.photoDao().upsertAll(photos.toList())

    /** Newest first, matching [fr.enry.archivist.data.local.db.PhotoDao.observeTimeline]'s
     * own tiebreak — `p5` is newest, `p1` oldest. */
    private val p5 = photo("p5", "2024-01-05T00:00:00.000Z")
    private val p4 = photo("p4", "2024-01-04T00:00:00.000Z")
    private val p3 = photo("p3", "2024-01-03T00:00:00.000Z")
    private val p2 = photo("p2", "2024-01-02T00:00:00.000Z")
    private val p1 = photo("p1", "2024-01-01T00:00:00.000Z")

    @Test
    fun `refresh with no key loads from the newest item`() =
        runTest {
            seed(p5, p4, p3)

            val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false))

            assertEquals(listOf("p5", "p4"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
            assertEquals(TimelineKey("2024-01-04T00:00:00.000Z", "p4"), result.nextKey)
        }

    @Test
    fun `append loads strictly older items after the given key, with no overlap`() =
        runTest {
            seed(p5, p4, p3, p2, p1)

            val result =
                source.load(
                    PagingSource.LoadParams.Append(key = TimelineKey("2024-01-04T00:00:00.000Z", "p4"), loadSize = 2, placeholdersEnabled = false),
                )

            assertEquals(listOf("p3", "p2"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    @Test
    fun `append with fewer remaining items than the page size reports no next key`() =
        runTest {
            seed(p5, p4, p3)

            val result =
                source.load(
                    PagingSource.LoadParams.Append(key = TimelineKey("2024-01-04T00:00:00.000Z", "p4"), loadSize = 2, placeholdersEnabled = false),
                )

            assertEquals(listOf("p3"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
            assertNull(result.nextKey)
        }

    @Test
    fun `prepend loads strictly newer items before the given key, newest first`() =
        runTest {
            seed(p5, p4, p3, p2, p1)

            val result =
                source.load(
                    PagingSource.LoadParams.Prepend(key = TimelineKey("2024-01-02T00:00:00.000Z", "p2"), loadSize = 2, placeholdersEnabled = false),
                )

            assertEquals(listOf("p4", "p3"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    @Test
    fun `refresh from a key re-centers on that item inclusively`() =
        runTest {
            seed(p5, p4, p3, p2, p1)

            val result =
                source.load(
                    PagingSource.LoadParams.Refresh(key = TimelineKey("2024-01-03T00:00:00.000Z", "p3"), loadSize = 2, placeholdersEnabled = false),
                )

            assertEquals(listOf("p3", "p2"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    @Test
    fun `getRefreshKey resolves to the anchor item's own identity, not its position`() =
        runTest {
            val state =
                PagingState(
                    pages =
                        listOf(
                            PagingSource.LoadResult.Page(
                                data = listOf(p5, p4, p3),
                                prevKey = null,
                                nextKey = TimelineKey("2024-01-03T00:00:00.000Z", "p3"),
                            ),
                        ),
                    anchorPosition = 2,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            assertEquals(TimelineKey("2024-01-03T00:00:00.000Z", "p3"), source.getRefreshKey(state))
        }

    @Test
    fun `getRefreshKey returns null right after a jump, ignoring a stale anchor`() =
        runTest {
            // p1 is the anchor from *before* the jump -- no longer in the table at all
            // once TimelineRemoteMediator has cleared+reseeded it around the jump
            // target, exactly as it would post-jump.
            seed(p3, p4)
            jumpCoordinator.markJustReset()
            val state =
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page<TimelineKey, PhotoEntity>(data = listOf(p1), prevKey = null, nextKey = null)),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            assertNull(source.getRefreshKey(state))
        }

    @Test
    fun `getRefreshKey falls back to the anchor once the just-reset flag has been consumed`() =
        runTest {
            jumpCoordinator.markJustReset()
            source.getRefreshKey(emptyRefreshState()) // consumes the flag

            val state =
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page<TimelineKey, PhotoEntity>(data = listOf(p3), prevKey = null, nextKey = null)),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            assertEquals(TimelineKey("2024-01-03T00:00:00.000Z", "p3"), source.getRefreshKey(state))
        }

    /** The scenario the class doc calls out by name: jumping *forward*, but not all the
     * way to the newest end, from an anchor the old (pre-jump) generation left behind.
     * Trusting that stale anchor's `takenAt < :anchor` condition against the *new*
     * (post-jump) window -- whose rows are all *newer* than the stale anchor here --
     * would silently exclude everything and load nothing at all, rather than the top of
     * the jumped-to window. */
    @Test
    fun `a forward jump not reaching the newest end still lands on the new window, not the stale anchor`() =
        runTest {
            // The jump target sits between p2 and p4 -- TimelineRemoteMediator would
            // have reseeded exactly this window (p3, p2 here, newest of the window
            // first) around it. The anchor left behind is p1 -- older than everything
            // in the new window, which is what makes the naive anchor-based key wrong.
            seed(p3, p2)
            jumpCoordinator.markJustReset()
            val staleState =
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page<TimelineKey, PhotoEntity>(data = listOf(p1), prevKey = null, nextKey = null)),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            val refreshKey = source.getRefreshKey(staleState)
            assertNull(refreshKey)

            val result = source.load(PagingSource.LoadParams.Refresh(key = refreshKey, loadSize = 2, placeholdersEnabled = false))
            assertEquals(listOf("p3", "p2"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    private fun emptyRefreshState() =
        PagingState<TimelineKey, PhotoEntity>(pages = emptyList(), anchorPosition = null, config = config, leadingPlaceholderCount = 0)

    /** The actual regression this class exists to fix: a `RemoteMediator`-triggered
     * generation restart re-anchors on the item the user was actually viewing even
     * after new rows were inserted *ahead* of it (exactly what
     * `PhotoRepository.refreshLatest` does on every finished upload) — unlike a
     * positional/`Int`-offset key, which would resolve to a different row once
     * everything below the insertion point shifted. */
    @Test
    fun `refresh re-anchors on the same item even after newer rows were inserted ahead of it`() =
        runTest {
            seed(p3, p2, p1)
            val anchorKey = TimelineKey("2024-01-02T00:00:00.000Z", "p2")

            // Simulates PhotoRepository.refreshLatest() upserting newly-uploaded
            // photos, newer than everything already cached, while the user is
            // scrolled down at p2 -- the exact race that shifts a row *offset*.
            seed(p5, p4)

            val result = source.load(PagingSource.LoadParams.Refresh(key = anchorKey, loadSize = 2, placeholdersEnabled = false))

            assertEquals(listOf("p2", "p1"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }
}
