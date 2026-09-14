package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.RemoteMediator
import androidx.room.Transactor
import androidx.room.useWriterConnection
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.HistogramEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.remote.ArchivistApiFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Server's own `MAX_LIMIT` (`routes/photos.ts`) is 200; this is a client-side choice
 * well under that, sized for grid smoothness rather than round-trip count. */
private const val TIMELINE_PAGE_SIZE = 60

/** The fast-scroll range — `GET /photos/bounds`, design.md pattern 14. */
data class TimelineBounds(val oldest: Instant, val newest: Instant)

/**
 * Live photos per local day — `GET /photos/histogram`, design.md pattern 15.
 *
 * Keyed by the photo's *own* recorded offset, the same rule
 * [fr.enry.archivist.data.local.db.localDate] and the grid's date headers use, so a day
 * here is the same day the user sees drawn above a row of photos.
 */
data class TimelineHistogram(val days: Map<String, Int>, val total: Int)

/** 304 — what a revalidated histogram almost always comes back as. */
private const val HTTP_NOT_MODIFIED = 304

/** Where a jump ended up. [landing] is null only for "back to the present", which starts
 * at the newest cached photo. */
data class JumpOutcome(val landing: TimelineKey?)

/** How far a locally-answered jump scans for its landing. The match is within the
 * photos of one day plus the twelve-hour slack of [lastInstantOnOrBefore], so this is
 * generous; a scan that fills without a match falls through to the network. */
private const val LOCAL_LANDING_SCAN = 500

/** One page, at the server's own cap: the most new photos a finished upload can bring
 * into the window without leaving part of them for PREPEND. */
private const val REFRESH_LATEST_LIMIT = 200

/** Design.md's "fixed width" ISO-8601 convention, which a `to` bound compared
 * lexicographically against `timelineSk` has to honor exactly — unlike
 * [Instant.toString], which silently *drops* the fractional-seconds group whenever it's
 * precisely zero (`"…T00:00:00Z"`, not `"…T00:00:00.000Z"`), breaking that fixed width
 * for any jump target landing on an exact second. */
internal val ISO_MILLIS_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Plan step 2.11: the `Pager` wiring behind the timeline grid. [TimelinePagingSource]'s
 * own `TimelineKey` and [TimelineRemoteMediator]'s `GET /photos` cursor are two separate
 * things — see that class's doc.
 */
@Singleton
class PhotoRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val jumpCoordinator: TimelineJumpCoordinator,
    ) {
        @OptIn(ExperimentalPagingApi::class)
        private val mediator = TimelineRemoteMediator(instanceStore, archivistApiFactory, db, jumpCoordinator)

        /** [initialKey] is where a rebuilt pager starts — a jump's landing, so it is the
         * first row the grid shows. Null starts at the newest cached photo. */
        @OptIn(ExperimentalPagingApi::class)
        fun timeline(initialKey: TimelineKey? = null): Flow<PagingData<PhotoEntity>> =
            Pager(
                config = PagingConfig(pageSize = TIMELINE_PAGE_SIZE, enablePlaceholders = false),
                initialKey = initialKey,
                remoteMediator = mediator,
                pagingSourceFactory = { TimelinePagingSource(db, db.photoDao(), jumpCoordinator) },
            ).flow

        /**
         * A fast-scroll drag was released on [target] — replace the cache with a window
         * anchored there. A null [target] means "back to the present", which is both the
         * top of the scrollbar's own track and the escape hatch out of a jumped-to
         * position.
         *
         * Calls the mediator *directly* rather than staging a target and asking the UI
         * for a `LazyPagingItems.refresh()`. That indirection was the first version's
         * bug: `refresh()` invalidates the pager before the mediator has fetched
         * anything, so the pager re-anchors against the old cache and several
         * generations flip past before settling — visible as the grid bouncing around
         * and landing somewhere other than the date that was picked. Going straight to
         * the mediator means exactly one write, and therefore exactly one new generation.
         */
        @OptIn(ExperimentalPagingApi::class)
        /**
         * Moves the timeline to [day] (null: back to the present), and reports where it
         * landed — or null if it couldn't, in which case the cache is untouched.
         *
         * Answered from Room with **no request at all** whenever the cache already holds
         * the day: see [cachedLanding]. Only a day outside the cached span reaches the
         * server, and then as a single bounded query — sized down to a screenful for a
         * [scrub], since most of those are superseded by the next finger position within
         * a fraction of a second and DynamoDB bills by items read.
         */
        suspend fun jumpTo(
            day: LocalDate?,
            scrub: Boolean = false,
        ): JumpOutcome? {
            if (day != null) cachedLanding(day)?.let { return JumpOutcome(it) }
            val result = mediator.reseedAt(day, if (scrub) SCRUB_PAGE_SIZE else RESEED_PAGE_SIZE)
            if (result is RemoteMediator.MediatorResult.Error) return null
            return JumpOutcome(jumpCoordinator.landing)
        }

        /**
         * The landing for [day], if the cache can vouch for it without asking the server.
         *
         * It can when the cached window is complete through [lastInstantOnOrBefore] — the
         * latest instant a photo can carry that day's header — because contiguity then
         * guarantees every photo that could be the landing is already here. The landing is
         * chosen by the same rule the server applies (`landingIndexFor`), so a day
         * resolved locally lands exactly where the same day fetched would have.
         *
         * Null sends the caller to the network: the window stops short of the day, the
         * day is older than anything cached, or nothing cached belongs to it or earlier.
         */
        private suspend fun cachedLanding(day: LocalDate): TimelineKey? {
            val boundIso = ISO_MILLIS_UTC.format(lastInstantOnOrBefore(day))
            val completeThrough = db.timelineWindowDao().completeThrough()
            if (completeThrough != null && completeThrough < boundIso) return null
            val candidates = db.photoDao().pageAtOrBefore(boundIso, LOCAL_LANDING_SCAN)
            val landing = candidates.firstOrNull { it.localDate() <= day } ?: return null
            val key = TimelineKey(landing.takenAt, landing.photoId)
            // Skipped, not re-staged, when this is already the live landing — the fix for
            // the "grid drifts forward through time on its own after a jump lands" bug
            // (see TimelineRemoteMediator's own "ran away" doc for the earlier, related
            // incident). jumpTo is called on every scrub during a drag and again on the
            // commit, and once the drag's very first (network) landing has cached the
            // day, every later call for the same day — the common case, since a drag
            // lingers on or returns to the same day repeatedly, and the release almost
            // always lands exactly where the last scrub already put things — resolves
            // locally right back to the identical key. Restaging it anyway used to force
            // the *next* invalidation-triggered PagingSource generation to reload from
            // scratch at that key via pageFromKey (no lead, unlike refreshAround) and then
            // eagerly walk forward through however much is cached in the PREPEND direction
            // to satisfy Paging's own internal prefetch bookkeeping — network round trips
            // included once local supply ran out, each writing more rows and therefore
            // invalidating again. One such reload after a genuine jump is normal and
            // wanted; the same reload repeating on every scrub event compounded it into
            // exactly the observed "walks forward through months of content by itself"
            // symptom, still running seconds after the finger had already lifted because
            // the last scrub and the commit each queued up one more redundant round. A key
            // that hasn't changed needs no restage: the paging source is already correctly
            // anchored there, or will settle there on its own from the one restage that
            // already happened.
            if (key != jumpCoordinator.landing) jumpCoordinator.stageLanding(key)
            return key
        }

        /**
         * The cached histogram (design.md pattern 15), served straight from Room so the
         * scrollbar has it the instant a long press opens the rail rather than after a
         * round trip.
         */
        fun histogram(): Flow<TimelineHistogram?> =
            db.histogramDao().observe().map { row ->
                row?.let { TimelineHistogram(days = it.days, total = it.total) }
            }

        /**
         * Revalidates the cached histogram against the server, replaying its `ETag` as
         * `If-None-Match`.
         *
         * The usual answer is a bodiless 304, which costs the server one item read
         * instead of a whole-partition query and serialisation — the histogram only
         * changes when a photo is added or trashed, so most calls change nothing. Silent
         * on failure for the same reason [fetchTimelineBounds] is: a stale or missing
         * histogram degrades the scrollbar rather than blocking the grid.
         */
        suspend fun syncHistogram() {
            val instance = instanceStore.current.first() ?: return
            val dao = db.histogramDao()
            val cached = dao.get()
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val response = api.getPhotosHistogram(photosHistogramUrl(instance.document.apiBase), cached?.etag)
            if (response.code() == HTTP_NOT_MODIFIED) return
            val body = response.body() ?: return
            dao.set(
                HistogramEntity(
                    days = body.days,
                    total = body.total,
                    etag = response.headers()["etag"],
                ),
            )
        }

        /** The scrollbar's own range — fetched fresh each call, best-effort (a stale or
         * missing range just means the scrollbar can't position itself precisely yet,
         * not a screen-blocking failure). */
        suspend fun fetchTimelineBounds(): TimelineBounds? {
            val instance = instanceStore.current.first() ?: return null
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val response = api.getPhotosBounds(photosBoundsUrl(instance.document.apiBase))
            val oldest = response.oldest?.let { Instant.parse(it) } ?: return null
            val newest = response.newest?.let { Instant.parse(it) } ?: return null
            return TimelineBounds(oldest, newest)
        }

        /**
         * Primes the *next* paging generation to land on [photoId], without any network
         * round trip — for a caller that already knows the photo is cached, e.g. it was
         * just shown in [fr.enry.archivist.ui.detail.DetailScreen]. Unlike [jumpTo], this
         * never touches the mediator: the photo is already in Room by construction (it
         * was just on screen), so there's nothing to fetch, only a key to stage.
         *
         * **Found live**: repairing a photo's thumbnails from `DetailScreen` writes the
         * fresh `#META` straight into `photos` (`RepairRepository`) so the fix shows up
         * without waiting on [refreshLatest]'s "newest end of the range" window, which an
         * older repaired photo would never fall inside. That write invalidates
         * [TimelinePagingSource] like any other — but it happens while `DetailScreen`
         * covers the grid, i.e. `TimelineGrid` isn't composed and nothing is reporting an
         * `anchorPosition` to page against. The eventual restart's `getRefreshKey` then
         * falls back to whatever anchor was last recorded before `DetailScreen` opened,
         * which has no particular relationship to where the grid was left — reported live
         * as the timeline "shifting wildly" on the way back out. Explicitly staging a
         * landing on the photo the user was actually looking at, right as they leave
         * `DetailScreen`, sidesteps relying on that anchor for this one transition — the
         * caller still has to force the restart itself (`LazyPagingItems.refresh()`),
         * since only Compose owns the `Pager` this stages into.
         */
        suspend fun stageLandingOn(photoId: String) {
            val photo = db.photoDao().getByPhotoId(photoId) ?: return
            jumpCoordinator.stageLanding(TimelineKey(photo.takenAt, photo.photoId))
        }

        /** Plan step 2.12: the plain (non-`Paging`) mirror of [timeline]'s own ordering,
         * for the detail screen's swipe-between-photos — index navigation over
         * `LazyPagingItems` doesn't compose cleanly with `HorizontalPager` once headers
         * are mixed in (see `TimelineScreen`'s grid), so the detail screen paginates
         * over this list directly instead. Same source, same order, just not chunked. */
        fun observeTimeline(): Flow<List<PhotoEntity>> = db.photoDao().observeTimeline()

        /** Called once per finished upload (see [UploadEvents]'s own doc) to fold the
         * newest server-known photos into the local cache. Deliberately *not*
         * `LazyPagingItems.refresh()`: that goes through [TimelineRemoteMediator]'s
         * `REFRESH` branch, which clears the whole `photos` table before repopulating
         * page one — fine for a cold start or an explicit pull-to-refresh (both start
         * the user at the top already), but it briefly deletes every older, already
         * -loaded page out from under whatever the user is currently scrolled through,
         * which is what made the grid visibly jump back to the top on every upload. A
         * plain upsert instead lets Room's own `PagingSource` invalidation (which
         * `pagingSourceFactory` already reacts to) slot the new photo in without
         * disturbing anything else or re-entering the `RemoteMediator` at all. */
        suspend fun refreshLatest() {
            // Browsing a past window: the new photo belongs months away from the cache,
            // and writing it in would leave a hole that keyset paging scrolls straight
            // across — silently skipping everything between. It arrives via PREPEND when
            // the user scrolls up to it, or on the way back to the present.
            if (db.timelineWindowDao().completeThrough() != null) return

            val instance = instanceStore.current.first() ?: return
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val newestCached = db.photoDao().pageFromStart(1).firstOrNull()
            if (newestCached == null) {
                val response = api.getPhotos(photosUrl(instance.document.apiBase), cursor = null, limit = TIMELINE_PAGE_SIZE)
                db.photoDao().upsertAll(response.items.map { it.toEntity() })
                return
            }
            // Only what is newer than the cache, oldest-first from its edge — which is
            // contiguous by construction, and for the usual single finished upload is one
            // or two items rather than the sixty the newest page used to re-read.
            val response =
                api.getPhotos(
                    photosUrl(instance.document.apiBase),
                    limit = REFRESH_LATEST_LIMIT,
                    from = newestCached.takenAt,
                    to = FAR_FUTURE_ISO,
                    order = "asc",
                )
            db.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    db.photoDao().upsertAll(response.items.map { it.toEntity() })
                    // A full page means there may be more beyond it: the window now stops
                    // there, and PREPEND takes it the rest of the way when scrolled to.
                    if (response.items.size >= REFRESH_LATEST_LIMIT) {
                        db.timelineWindowDao().setCompleteThrough(response.items.last().takenAt)
                    }
                }
            }
        }
    }
