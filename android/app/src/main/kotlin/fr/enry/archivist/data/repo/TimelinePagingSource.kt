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
     * The one exception is a fast-scroll jump, which stages its landing key on
     * [TimelineJumpCoordinator]: the previous generation's anchor refers to wherever the
     * user happened to be *before* the jump, which has nothing to do with where they
     * asked to go. Starting the generation at the staged key instead puts the requested
     * photo at index 0, so the grid lands on it without anyone having to compute an
     * index against a list that is still being replaced — see that class's doc for the
     * bug that approach shipped with. Photos newer than the landing point stay in the
     * cache and are reached by scrolling up, via this source's own `Prepend`. */
    override fun getRefreshKey(state: PagingState<TimelineKey, PhotoEntity>): TimelineKey? {
        jumpCoordinator.consumeLanding()?.let { return it }
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
                        params.key?.let { refreshAround(it, limit) }
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

    /**
     * A keyed refresh loads a window *around* [key], not one starting at it — the
     * difference between the timeline holding still and it walking into the past on its
     * own.
     *
     * [PagingState.anchorPosition] is the most recently *accessed* index, and a
     * `LazyVerticalGrid` composes well beyond what it displays, so the anchor routinely
     * sits a screenful of items further down the list than the photo actually on screen.
     * A page that began at the anchor therefore did not contain the visible photo at all;
     * `LazyVerticalGrid`'s key-based position restoration found nothing to restore to,
     * fell back to the raw index, and the grid silently started showing an item some days
     * older — then `Prepend` refilled the missing head, moving the index but not the
     * content. Since every write to `photos` (each `APPEND` the mediator makes, every
     * finished upload) restarts this generation, that ran as a ratchet: each round
     * re-anchored deeper and dragged the view a few days further back, which is what a
     * jump looked like "stepping" its way to a date years off target, and what the
     * runaway prepends behind an earlier ANR actually were.
     *
     * Loading [REFRESH_LEAD_FRACTION] of the page from *newer* than the key keeps
     * whatever the grid is displaying inside the new page, so restoration succeeds and
     * the view stays exactly where it was. It costs nothing on a fast-scroll landing: the
     * reseed has just cleared everything newer than the target, so the lead comes back
     * empty and the landing photo is still the page's first item.
     */
    private suspend fun refreshAround(
        key: TimelineKey,
        limit: Int,
    ): List<PhotoEntity> {
        val lead = photoDao.pageBefore(key.takenAt, key.photoId, limit / REFRESH_LEAD_FRACTION)
        return lead + photoDao.pageFromKey(key.takenAt, key.photoId, limit - lead.size)
    }
}

/** One third of a refresh page is spent on items newer than the anchor. The lead only
 * has to outrun how far `LazyVerticalGrid` composes ahead of the viewport — measured at
 * 16-28 items on a phone-sized grid — and a third of `initialLoadSize` clears that with
 * room to spare without meaningfully shrinking what a refresh loads below the anchor. */
private const val REFRESH_LEAD_FRACTION = 3
