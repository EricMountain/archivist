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
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PhotosPageResponse
import fr.enry.archivist.data.remote.TimelineEntryDto
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
 * `APPEND` walks older via the stored server cursor; `PREPEND` walks newer via an
 * ascending range from the newest cached photo ([loadNewerThanCache]).
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
            LoadType.REFRESH -> reseedAt(null)
            LoadType.PREPEND -> loadNewerThanCache()
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
    suspend fun reseedAt(day: LocalDate?): MediatorResult {
        return try {
            val instance = instanceStore.current.first() ?: return MediatorResult.Error(IllegalStateException("no connected instance"))
            val api = apiFor(instance)
            val url = photosUrl(instance.document.apiBase)

            val toIso = day?.let { ISO_MILLIS_UTC.format(lastInstantOnOrBefore(it)) }
            val page = api.getPhotos(url, cursor = null, limit = RESEED_PAGE_SIZE, from = day?.let { EPOCH_ISO }, to = toIso)

            // A jump that matched nothing keeps the cache it already had, rather than
            // clearing it and committing an empty one. An empty timeline is a dead end:
            // there is no row for `PREPEND` to work back from and no cursor for `APPEND`
            // to follow, so the grid shows "No photos yet" until the process is restarted
            // — which is exactly what a drag to the very bottom of the rail used to do.
            // [jumpTargetFor] no longer produces such a target, but a jump is a whole-
            // cache replacement and is not worth leaving one bad bound away from that.
            // A *plain* refresh is exempt: there, empty genuinely means an empty library.
            if (day != null && page.items.isEmpty()) {
                return MediatorResult.Error(IllegalStateException("no photos on or before $day"))
            }

            // The bound is deliberately loose (see [lastInstantOnOrBefore]), so the head of
            // the page can hold photos that belong to a *later* day than the one picked.
            // Dropping them is what makes the landing the newest photo whose own header
            // reads the requested date, and keeps it the first row of the window — which
            // both `getRefreshKey`'s staged landing and a rebuilt `Pager`'s `pageFromStart`
            // rely on to put it at the top of the grid.
            val entities = page.items.map { it.toEntity() }
            val window = if (day == null) entities else entities.drop(entities.landingIndexFor(day))
            val landOn = window.firstOrNull()

            // Staged *before* the write, not after: Room's InvalidationTracker can fire
            // as part of the transaction commit itself, and the next generation's
            // getRefreshKey has to see this already staged rather than lose a race with
            // it. A null key (nothing to land on, or a plain refresh) leaves
            // getRefreshKey to its ordinary anchor-based behaviour.
            jumpCoordinator.stageLanding(landOn?.let { TimelineKey(it.takenAt, it.photoId) })
            db.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    photoDao.clear()
                    photoDao.upsertAll(window)
                    // The cursor tracks the older direction only, which is the one
                    // ordinary scrolling continues in.
                    if (page.cursor != null) {
                        timelineCursorDao.set(TimelineCursorEntity(cursor = page.cursor, updatedAt = nowIso()))
                    } else {
                        timelineCursorDao.clear()
                    }
                }
            }

            MediatorResult.Success(endOfPaginationReached = page.cursor == null)
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }

    /**
     * Fetches the page immediately *newer* than everything cached, so a position reached
     * by a fast-scroll jump can be scrolled back out of, toward the present — without it,
     * a jump leaves the timeline able to travel further into the past but nowhere else.
     * design.md's pattern 3b: an ascending range starting at the newest cached photo.
     *
     * This ran away once, growing the cache from ~160 rows to 639 in a single jump and
     * ending in an ANR, and was withdrawn. The cause was not here: [TimelinePagingSource]
     * refreshed onto a page that began at its own anchor, which dropped the displayed
     * photo out of the list, and the prepends that refilled the head were the symptom of
     * that ratchet rather than its cause — see `refreshAround`. With the page held
     * steady this terminates normally, on `endOfPaginationReached` as soon as the server
     * has nothing newer.
     */
    private suspend fun loadNewerThanCache(): MediatorResult {
        return try {
            // Inclusive `from`, so the newest cached photo comes back with the page and is
            // simply re-upserted; the alternative (a synthetic instant one millisecond
            // later) would silently skip any photo sharing that timestamp.
            val newestCached = photoDao.pageFromStart(1).firstOrNull() ?: return MediatorResult.Success(endOfPaginationReached = true)
            val instance = instanceStore.current.first() ?: return MediatorResult.Error(IllegalStateException("no connected instance"))
            val response =
                apiFor(instance).getPhotos(
                    photosUrl(instance.document.apiBase),
                    limit = PREPEND_PAGE_SIZE,
                    from = newestCached.takenAt,
                    to = FAR_FUTURE_ISO,
                    order = "asc",
                )

            // Deliberately not advancing the stored cursor: it tracks the older direction,
            // which ordinary scrolling continues in, and this page's cursor walks the
            // other way.
            writePage(response, clearFirst = false, advanceCursor = false)
            // The boundary photo itself is always returned, so "nothing but the boundary"
            // is what "already at the present" looks like.
            MediatorResult.Success(endOfPaginationReached = response.items.none { it.photoId != newestCached.photoId })
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

/** Shared with [PhotoRepository.refreshLatest] — same endpoint, same URL shape. */
internal fun photosUrl(apiBase: String) = "$apiBase/photos"

/** [PhotoRepository.fetchTimelineBounds]'s URL — `GET /photos/bounds` in api.md. */
internal fun photosBoundsUrl(apiBase: String) = "$apiBase/photos/bounds"

/** A jump's `from` bound: old enough that no real photo predates it, so `to` alone
 * effectively bounds the query — the server requires both or neither (`routes/photos.ts`). */
internal const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"

/**
 * The latest UTC instant at which a photo can still *belong* to [day], for any recorded
 * offset — i.e. the `to` bound that is guaranteed not to exclude a photo the grid would
 * header with that date.
 *
 * A photo's day is `takenAt` shifted by its own `tzOffsetMin` ([localDate]), and the
 * furthest west any real offset goes is UTC−12, so a photo can carry [day]'s header until
 * twelve hours after that day has ended in UTC. Bounding at the end of the day in the
 * *viewer's* zone instead is what made the pill and the header disagree: it excluded
 * exactly those photos, landing the jump a day early.
 *
 * The bound is therefore deliberately loose in the other direction — it also admits
 * photos belonging to the *next* day — and `reseedAt` drops those from the head of the
 * page rather than trying to express the condition in the query. It can't be expressed
 * there: `timeline_gsi` is keyed on UTC `takenAt`, and a photo's offset isn't part of the
 * key at all.
 */
internal fun lastInstantOnOrBefore(day: LocalDate): Instant =
    day.plusDays(1).atStartOfDay().plusHours(MAX_HOURS_WEST_OF_UTC).toInstant(ZoneOffset.UTC).minusMillis(1)

private const val MAX_HOURS_WEST_OF_UTC = 12L

/** Index of the newest photo whose own header date is at or before [day] — the photo a
 * jump to that day should land on. Falls back to the oldest photo fetched when every one
 * of them belongs to a later day, so a jump always lands somewhere rather than committing
 * an empty window. */
internal fun List<PhotoEntity>.landingIndexFor(day: LocalDate): Int =
    indexOfFirst { it.localDate() <= day }.takeIf { it >= 0 } ?: lastIndex

/** [TimelineRemoteMediator.loadNewerThanCache]'s `to` bound: far enough ahead that no
 * real photo postdates it, so `from` alone effectively bounds the query. Mirrors
 * [EPOCH_ISO] at the other end of time. */
internal const val FAR_FUTURE_ISO = "9999-12-31T23:59:59.999Z"

/** Walking back toward the present is a deliberate, user-driven scroll rather than a
 * background sweep, so it moves a screenful at a time — smaller than [RESEED_PAGE_SIZE],
 * which replaces the whole cache in one go. */
private const val PREPEND_PAGE_SIZE = 60

/** A reseed (jump, or plain refresh) deliberately fetches more than one pager page:
 * it's replacing the entire cache, so a single page's worth would leave the user one
 * short scroll away from the edge in both directions. Server caps `limit` at 200. */
private const val RESEED_PAGE_SIZE = 120

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
