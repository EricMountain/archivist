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

    /**
     * The timeline's "stepping into the past on its own" bug, pinned down live (STATUS.md,
     * plan step 2.11). `anchorPosition` is the last *accessed* index and a
     * `LazyVerticalGrid` composes well past what it shows, so the refresh key names an
     * item below the viewport. A page beginning there excluded the photo actually on
     * screen, the grid's key-based restoration had nothing to match, and it fell back to
     * the raw index — landing on an older photo. Every write to `photos` restarts this
     * generation, so it repeated, walking the view days further back each time.
     *
     * Loading part of the page from newer than the key keeps the displayed photo inside
     * it, which is what stops the drift.
     */
    @Test
    fun `a keyed refresh includes items newer than the key, so the displayed one stays in the page`() =
        runTest {
            seed(p5, p4, p3, p2, p1)

            val result =
                source.load(
                    PagingSource.LoadParams.Refresh(key = TimelineKey("2024-01-02T00:00:00.000Z", "p2"), loadSize = 6, placeholdersEnabled = false),
                )

            // loadSize 6 -> a lead of 2 newer than p2, then p2 itself and what follows.
            assertEquals(listOf("p4", "p3", "p2", "p1"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    /** A fast-scroll landing must still sit at index 0: the reseed has just deleted
     * everything newer than the target, so the lead comes back empty of its own accord
     * rather than needing to be special-cased away. */
    @Test
    fun `the lead is empty at the top of the table, leaving a jump landing first`() =
        runTest {
            seed(p3, p2, p1)

            val result =
                source.load(
                    PagingSource.LoadParams.Refresh(key = TimelineKey("2024-01-03T00:00:00.000Z", "p3"), loadSize = 6, placeholdersEnabled = false),
                )

            assertEquals(listOf("p3", "p2", "p1"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
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

    /** A jump stages the photo at the requested instant, and the next generation starts
     * there — the whole point being that the grid then lands on it at index 0, with no
     * index computed against a list that is still being replaced. */
    @Test
    fun `getRefreshKey starts the generation at a staged jump landing, ignoring the stale anchor`() =
        runTest {
            seed(p3, p2)
            jumpCoordinator.stageLanding(TimelineKey("2024-01-03T00:00:00.000Z", "p3"))
            // p1 is where the user was *before* the jump, and has nothing to do with
            // where they asked to go.
            val staleState =
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page<TimelineKey, PhotoEntity>(data = listOf(p1), prevKey = null, nextKey = null)),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            val refreshKey = source.getRefreshKey(staleState)
            assertEquals(TimelineKey("2024-01-03T00:00:00.000Z", "p3"), refreshKey)

            // Loading from it puts the landing photo first, so "go to the top" is right.
            val result = source.load(PagingSource.LoadParams.Refresh(key = refreshKey, loadSize = 2, placeholdersEnabled = false))
            assertEquals(listOf("p3", "p2"), (result as PagingSource.LoadResult.Page).data.map { it.photoId })
        }

    @Test
    fun `a staged landing is consumed once, then getRefreshKey falls back to the anchor`() =
        runTest {
            jumpCoordinator.stageLanding(TimelineKey("2024-01-05T00:00:00.000Z", "p5"))
            assertEquals(TimelineKey("2024-01-05T00:00:00.000Z", "p5"), source.getRefreshKey(emptyRefreshState()))

            val state =
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page<TimelineKey, PhotoEntity>(data = listOf(p3), prevKey = null, nextKey = null)),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0,
                )

            assertEquals(TimelineKey("2024-01-03T00:00:00.000Z", "p3"), source.getRefreshKey(state))
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
