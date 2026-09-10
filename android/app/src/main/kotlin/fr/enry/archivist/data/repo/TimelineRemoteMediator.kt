package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.RemoteMediator.InitializeAction
import androidx.room.Transactor
import androidx.room.useWriterConnection
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineCursorEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PhotosPageResponse
import fr.enry.archivist.data.remote.TimelineEntryDto
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Plan step 2.11: "network fills Room, Room feeds the pager" (android.md). The
 * [TimelineKey] [PagingState] itself works with is [TimelinePagingSource]'s own item-
 * identity key (`takenAt#photoId`) — unrelated to `GET /photos`'s opaque cursor string,
 * which this class tracks separately via [TimelineCursorEntity], the same decoupling the
 * "network + database" `RemoteMediator` recipe always uses.
 *
 * `REFRESH` re-fetches from the newest photo and clears everything else — the standard
 * recipe, and safe here because it only touches the cache, never anything server-side.
 * [reseedAt] is the same operation anchored at an arbitrary instant instead, for a
 * fast-scroll jump. Offline-first falls out of the architecture rather than needing its
 * own code: a fetch attempted with no network throws before this ever touches Room, so
 * whatever was already cached from a previous session stays exactly as it was, and the
 * `PagingSource` above keeps serving it directly from Room regardless of whether this
 * mediator's own fetch just failed.
 *
 * All three load types fetch: `APPEND` walks older via the stored server cursor,
 * `PREPEND` walks newer via an ascending range query (see [loadNewerThanCache]). The
 * latter exists only because jumps do — with the cache always anchored at the newest
 * photo there is nothing newer to load, which is why it was a no-op until fast-scroll
 * made "cache anchored mid-library" a reachable state.
 *
 * [initialize] matters more than it looks, and independently of [TimelinePagingSource]'s
 * own item-keyed fix for the same symptom: any write to `photos` — including this very
 * class's own `APPEND` upsert below, or [PhotoRepository.refreshLatest]'s — invalidates
 * the `pagingSourceFactory`-generated `PagingSource` and starts a new `Pager`
 * generation. `RemoteMediator`'s own default `initialize()` is `LAUNCH_INITIAL_REFRESH`,
 * which fires a fresh mediator `REFRESH` for *every* such generation — i.e. this
 * mediator would re-enter itself after its own append and `clear()` everything just
 * upserted (and every older page loaded before it), deleting the very row the user was
 * anchored on out from under an otherwise-correct keyed re-anchor: `clear()` leaves
 * nothing for [TimelinePagingSource.load]'s `pageFromKey` to find at that key at all,
 * which would show up as the grid going momentarily empty rather than merely jumping.
 */
@OptIn(ExperimentalPagingApi::class)
class TimelineRemoteMediator(
    private val instanceStore: InstanceStore,
    private val archivistApiFactory: ArchivistApiFactory,
    private val db: AppDatabase,
    private val jumpCoordinator: TimelineJumpCoordinator,
) : RemoteMediator<TimelineKey, PhotoEntity>() {
    private val photoDao = db.photoDao()
    private val timelineCursorDao = db.timelineCursorDao()

    /** Only a genuinely empty cache (true cold start) needs the automatic `REFRESH`
     * `LAUNCH_INITIAL_REFRESH` would trigger — see the class doc above. Every other new
     * generation was caused by a write this mediator (or [PhotoRepository.refreshLatest])
     * already made, so the existing rows just need to keep being served as-is. */
    override suspend fun initialize(): InitializeAction =
        if (photoDao.isEmpty()) InitializeAction.LAUNCH_INITIAL_REFRESH else InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<TimelineKey, PhotoEntity>,
    ): MediatorResult {
        return when (loadType) {
            LoadType.REFRESH -> reseedAt(null).result
            LoadType.PREPEND -> loadNewerThanCache(state.config.pageSize)
            LoadType.APPEND -> loadOlder(state.config.pageSize)
        }
    }

    /**
     * Replaces the whole cache with a page anchored at [targetIso] (a fast-scroll jump),
     * or at the newest photo when it's null (an ordinary `REFRESH`, and the way back to
     * the present after a jump). Public because [PhotoRepository.jumpTo] calls it
     * *directly* rather than going through `LazyPagingItems.refresh()`: that route
     * invalidates the pager before this has fetched anything, so
     * [TimelinePagingSource.getRefreshKey] runs against the old cache with the reset flag
     * not yet set — several generations flip past before settling, which is exactly the
     * "bounces around the timeline a few times, then lands nowhere near the date I
     * picked" the first version of this feature shipped with.
     */
    suspend fun reseedAt(targetIso: String?): ReseedOutcome {
        return try {
            val instance = instanceStore.current.first() ?: return ReseedOutcome(MediatorResult.Error(IllegalStateException("no connected instance")))
            val api = apiFor(instance)
            val url = photosUrl(instance.document.apiBase)

            val older = api.getPhotos(url, cursor = null, limit = RESEED_PAGE_SIZE, from = targetIso?.let { EPOCH_ISO }, to = targetIso)
            // A jump also fetches a little of what comes *after* the target, so the
            // landing sits at the requested date as a boundary rather than dumping the
            // user at the newest photo that happens to predate it — which, across a gap
            // in the library, is arbitrarily far from where they pointed. It also keeps
            // the landing off index 0, so an immediate `PREPEND` doesn't fire and shove
            // the view down the moment it settles.
            val newer =
                if (targetIso == null) {
                    null
                } else {
                    api
                        .getPhotos(url, cursor = null, limit = RESEED_CONTEXT_SIZE, from = targetIso, to = FAR_FUTURE_ISO, order = "asc")
                        // Same guard as loadNewerThanCache: an instance without
                        // `order=asc` answers with the top of the library instead.
                        .takeIf { it.looksAscending() }
                }

            // TimelinePagingSource.getRefreshKey's own re-anchor logic assumes the
            // *previous* generation's scroll position is still meaningful -- true for an
            // ordinary refresh, false for a jump, which reseeds around a target having
            // nothing to do with where the user was scrolled. Set *before* the write
            // below, not after: Room's InvalidationTracker can fire as part of the
            // transaction commit itself, and the next generation's getRefreshKey has to
            // see this flag already set rather than lose a race with it.
            jumpCoordinator.markJustReset()
            db.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    photoDao.clear()
                    photoDao.upsertAll(older.items.map { it.toEntity() })
                    newer?.let { page -> photoDao.upsertAll(page.items.map { it.toEntity() }) }
                    // The cursor tracks the older direction only, which is the one
                    // ordinary scrolling continues in.
                    if (older.cursor != null) {
                        timelineCursorDao.set(TimelineCursorEntity(cursor = older.cursor, updatedAt = nowIso()))
                    } else {
                        timelineCursorDao.clear()
                    }
                }
            }

            // Newest-first, so the first of `older` is the photo immediately at or before
            // the target — the boundary itself. With nothing before it, the first of the
            // ascending page is the closest photo after it instead.
            val landOn = older.items.firstOrNull()?.photoId ?: newer?.items?.firstOrNull()?.photoId
            ReseedOutcome(MediatorResult.Success(endOfPaginationReached = older.cursor == null), landOn)
        } catch (e: IOException) {
            ReseedOutcome(MediatorResult.Error(e))
        } catch (e: HttpException) {
            ReseedOutcome(MediatorResult.Error(e))
        }
    }

    /**
     * `PREPEND` — the page immediately *newer* than what's cached, which only matters
     * once a jump has left the cache anchored somewhere in the middle of the library
     * rather than at the newest photo. Without it a jump is a one-way trip: scrolling
     * back toward the present dead-ends at the top of the jumped window.
     *
     * Needs design.md's pattern 3b (`order=asc`) rather than the timeline's usual
     * newest-first read: the rows wanted are the ones *adjacent* to the cache, i.e. the
     * oldest few of the range above it, not the newest few (which would be the top of
     * the whole library). `from` is inclusive, so the anchor row itself comes back as
     * the first item — a page containing only the anchor is how "nothing newer exists"
     * is told apart from "here are the next few", without a second query.
     */
    private suspend fun loadNewerThanCache(pageSize: Int): MediatorResult {
        return try {
            val newestCached = photoDao.pageFromStart(1).firstOrNull() ?: return MediatorResult.Success(endOfPaginationReached = true)
            val instance = instanceStore.current.first() ?: return MediatorResult.Error(IllegalStateException("no connected instance"))
            val response =
                apiFor(instance).getPhotos(
                    photosUrl(instance.document.apiBase),
                    cursor = null,
                    limit = pageSize,
                    from = newestCached.takenAt,
                    to = FAR_FUTURE_ISO,
                    order = "asc",
                )

            // An instance that predates `order=asc` ignores the parameter and answers
            // newest-first, which would be the top of the whole library rather than the
            // rows adjacent to the cache — caching those would splice a disjoint chunk
            // into the timeline. Degrade to the old "can't walk newer" behaviour instead.
            if (!response.looksAscending()) return MediatorResult.Success(endOfPaginationReached = true)

            val newRows = response.items.filterNot { it.photoId == newestCached.photoId }
            if (newRows.isEmpty()) return MediatorResult.Success(endOfPaginationReached = true)

            // Deliberately no clear() and no cursor write: this direction is additive,
            // and TimelineCursorEntity tracks the *append* (older) direction only.
            writePage(response, clearFirst = false, advanceCursor = false)
            MediatorResult.Success(endOfPaginationReached = false)
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun loadOlder(pageSize: Int): MediatorResult {
        return try {
            val cursor = timelineCursorDao.observe().first()?.cursor ?: return MediatorResult.Success(endOfPaginationReached = true)
            val instance = instanceStore.current.first() ?: return MediatorResult.Error(IllegalStateException("no connected instance"))
            val response =
                apiFor(instance).getPhotos(photosUrl(instance.document.apiBase), cursor = cursor, limit = pageSize)

            writePage(response, clearFirst = false, advanceCursor = true)
            MediatorResult.Success(endOfPaginationReached = response.cursor == null)
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }

    // Not androidx.room.withTransaction: that extension still routes through the legacy
    // SupportSQLiteOpenHelper-based transaction API, which a setDriver(...)-configured
    // database (see TestDatabase.kt) has none of -- useWriterConnection/Transactor is the
    // driver-based replacement, and works identically against a framework-backed
    // database too (this repo's real one).
    private suspend fun writePage(
        response: PhotosPageResponse,
        clearFirst: Boolean,
        advanceCursor: Boolean,
    ) {
        db.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                if (clearFirst) photoDao.clear()
                photoDao.upsertAll(response.items.map { it.toEntity() })
                if (advanceCursor) {
                    if (response.cursor != null) {
                        timelineCursorDao.set(TimelineCursorEntity(cursor = response.cursor, updatedAt = nowIso()))
                    } else {
                        timelineCursorDao.clear()
                    }
                }
            }
        }
    }

    private fun apiFor(instance: StoredInstance): ArchivistApi =
        archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
}

/** What [TimelineRemoteMediator.reseedAt] did, and which photo the caller should land
 * the grid on — the boundary at the requested instant, rather than whatever ends up at
 * index 0. Null when the reseed failed, or when the library is empty. */
@OptIn(ExperimentalPagingApi::class)
data class ReseedOutcome(
    val result: RemoteMediator.MediatorResult,
    val landOnPhotoId: String? = null,
)

/** Whether a page actually came back oldest-first. `takenAt` is fixed-width ISO-8601, so
 * lexicographic order is chronological order — see design.md's timestamp convention. */
internal fun PhotosPageResponse.looksAscending(): Boolean =
    items.size < 2 || items.first().takenAt <= items.last().takenAt

/** Shared with [PhotoRepository.refreshLatest] — same endpoint, same URL shape. */
internal fun photosUrl(apiBase: String) = "$apiBase/photos"

/** [PhotoRepository.fetchTimelineBounds]'s URL — `GET /photos/bounds` in api.md. */
internal fun photosBoundsUrl(apiBase: String) = "$apiBase/photos/bounds"

/** A jump's `from` bound: old enough that no real photo predates it, so `to` alone
 * effectively bounds the query — the server requires both or neither (`routes/photos.ts`). */
internal const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"

/** A `PREPEND`'s `to` bound, for the same reason [EPOCH_ISO] is a jump's `from`: the
 * range is really "everything newer than the anchor", and the server needs both ends. */
internal const val FAR_FUTURE_ISO = "9999-12-31T23:59:59.999Z"

/** A reseed (jump, or plain refresh) deliberately fetches more than one pager page:
 * it's replacing the entire cache, so a single page's worth would leave the user one
 * short scroll away from the edge in both directions. Server caps `limit` at 200. */
private const val RESEED_PAGE_SIZE = 120

/** How much of the timeline *after* a jump target to fetch alongside it, so the landing
 * shows the requested date as a boundary with later photos above it rather than as the
 * top of the list. Smaller than [RESEED_PAGE_SIZE] because scrolling onward from a jump
 * goes into the past, not out of it. */
private const val RESEED_CONTEXT_SIZE = 40

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** `thumbs`' JSON-object keys arrive as strings (`"256"`) even though the server's own
 * `ThumbMap` is keyed by number — see [TimelineEntryDto]'s own doc. */
internal fun TimelineEntryDto.toEntity(): PhotoEntity =
    PhotoEntity(
        photoId = photoId,
        takenAt = takenAt,
        tzOffsetMin = tzOffsetMin,
        mime = mime,
        width = width,
        height = height,
        status = AssetStatus.valueOf(status.uppercase()),
        thumbs = thumbs.mapKeys { it.key.toInt() },
        encDek = encDek,
        encKeyId = encKeyId,
    )
