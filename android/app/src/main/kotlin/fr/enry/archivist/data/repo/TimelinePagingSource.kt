package fr.enry.archivist.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey

/**
 * The local half of plan step 2.11's `Pager` (see [TimelineRemoteMediator]'s own doc for
 * the network half and why every write to `photos` -- including its own `APPEND` and
 * [PhotoRepository.refreshLatest]'s top-ups -- restarts this source's generation).
 *
 * Keyed on the anchor item's own `takenAt#photoId` identity ([TimelineKey]), not a row
 * offset: Room's own `@Query`-generated `PagingSource` is always positional (`Int`), and
 * a positional key doesn't survive a generation restart once anything has been inserted
 * *anywhere else* in the table -- which, for a `takenAt DESC` ordering, `refreshLatest`'s
 * newest-first inserts do on every single finished upload, shifting the row offset of
 * everything the user has already scrolled past. A restart that re-resolves the old raw
 * offset against the now-shifted table lands on a different, more-recent row than the
 * one the user was actually looking at -- visible as the grid "bouncing" back toward the
 * present while scrolling into the past. Keying on the item's own identity instead means
 * a restart re-centers on that exact row regardless of what got inserted elsewhere.
 *
 * Not Room-generated, so it hand-rolls the same [InvalidationTracker] wiring Room's own
 * generated `PagingSource` relies on ([observer]/`addWeakObserver`) to still invalidate
 * whenever `photos` changes.
 */
class TimelinePagingSource(
    db: AppDatabase,
    private val photoDao: PhotoDao,
    private val jumpCoordinator: TimelineJumpCoordinator,
) : PagingSource<TimelineKey, PhotoEntity>() {
    private val observer =
        object : InvalidationTracker.Observer("photos") {
            override fun onInvalidated(tables: Set<String>) = invalidate()
        }

    init {
        db.invalidationTracker.addWeakObserver(observer)
        registerInvalidatedCallback { db.invalidationTracker.removeObserver(observer) }
    }

    /** [PagingState.closestItemToPosition] is the standard pattern for an item-keyed
     * source (mirrors Android's own "network + item key" `PagingSource` recipe, just
     * applied to a local table): the anchor's own identity, not its position, survives
     * the restart this triggers.
     *
     * The one exception is a fast-scroll jump: [TimelineJumpCoordinator.consumeJustReset]
     * comes back `true` only for the generation immediately after
     * [TimelineRemoteMediator] clears+reseeds the table around a jump target, at which
     * point the *previous* generation's anchor refers to a row that has nothing to do
     * with where the user just asked to go. Trusting it anyway would silently mis-land
     * a jump that doesn't reach all the way to the newest end: e.g. jumping from an
     * anchor in 2019 forward to 2021 (not "now") would resolve to `takenAt < 2019`,
     * which — restricted to the new [EPOCH_ISO]`..2021` window — excludes everything
     * between 2019 and 2021 and lands back near 2019. Returning `null` instead forces
     * [PhotoDao.pageFromStart], which is unconditionally correct here: the table now
     * contains exactly the jumped-to window, so its own top *is* the jump target. */
    override fun getRefreshKey(state: PagingState<TimelineKey, PhotoEntity>): TimelineKey? {
        if (jumpCoordinator.consumeJustReset()) return null
        return state.anchorPosition?.let { anchor ->
            state.closestItemToPosition(anchor)?.let { TimelineKey(it.takenAt, it.photoId) }
        }
    }

    override suspend fun load(params: LoadParams<TimelineKey>): LoadResult<TimelineKey, PhotoEntity> =
        try {
            val limit = params.loadSize
            val page =
                when (params) {
                    is LoadParams.Refresh ->
                        params.key?.let { photoDao.pageFromKey(it.takenAt, it.photoId, limit) }
                            ?: photoDao.pageFromStart(limit)
                    is LoadParams.Append -> photoDao.pageAfter(params.key.takenAt, params.key.photoId, limit)
                    is LoadParams.Prepend -> photoDao.pageBefore(params.key.takenAt, params.key.photoId, limit)
                }
            LoadResult.Page(
                data = page,
                // Deliberately not null-on-refresh-from-start: an unnecessary prevKey
                // just costs one harmless Prepend call that comes back empty and
                // self-terminates (sets its own prevKey null) -- simpler and no less
                // correct than special-casing it away.
                prevKey = page.firstOrNull()?.let { TimelineKey(it.takenAt, it.photoId) },
                // Whether page.size == limit is only a heuristic ("maybe more"), not
                // certainty -- same trade-off Room's own generated positional source
                // makes. A wrong guess costs one extra load that comes back empty and
                // corrects itself, same as prevKey above.
                nextKey = page.lastOrNull()?.takeIf { page.size == limit }?.let { TimelineKey(it.takenAt, it.photoId) },
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}
