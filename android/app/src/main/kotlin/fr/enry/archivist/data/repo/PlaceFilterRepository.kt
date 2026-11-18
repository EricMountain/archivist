@file:OptIn(ExperimentalPagingApi::class)

package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.RemoteMediator.MediatorResult
import androidx.room.Transactor
import androidx.room.useWriterConnection
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.FacetStreamEntity
import fr.enry.archivist.data.local.db.FacetStreamRowEntity
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.FacetPageResponse
import fr.enry.archivist.data.remote.PlaceDto
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

/** One facet page: the grid's page, well under the server's cap of 200. Keeps the first
 * paint of a many-place selection cheap (one small request per place). */
internal const val FACET_STREAM_PAGE_SIZE = 60

/** At most this many facet requests in flight at once, across every stream (plan F6 and
 * "Awkward consequences": a broad query's first pages queue behind it rather than all
 * firing together). */
internal const val MAX_FACET_FETCHES_IN_FLIGHT = 6

/** How many rounds one APPEND may fetch while the merged view gains no photo (a page that
 * came back empty with a cursor, or a watermark shared by several streams). Bounded so a
 * misbehaving server can't spin it; Paging asks again on the next scroll. */
private const val MAX_APPEND_ROUNDS = 5

/**
 * One selected place as a facet stream (plan F1): `GET /facets/[facetType]/[value]`. The
 * place columns its rows carry are the stream's own (F12): a town gives its name and id,
 * a region its name and id, a country neither, since a country-only bubble shows the
 * date alone.
 */
data class FacetStreamSpec(
    val facetType: String,
    val value: String,
    val cc: String?,
    val placeName: String?,
    val placeKey: String?,
) {
    val key: String get() = "$facetType#$value"

    /** Which row wins when a photo is in two selected streams: the more specific one. */
    val specificity: Int
        get() =
            when (facetType) {
                "PLACE" -> 2
                "REGION" -> 1
                else -> 0
            }
}

/** `GET /facets/{type}/{value}`, one page. A seam for tests; the app's goes through
 * [ArchivistApiFactory]. */
fun interface FacetPageFetcher {
    suspend fun fetch(
        facetType: String,
        value: String,
        cursor: String?,
        limit: Int,
    ): FacetPageResponse
}

/** What the merged view of a selection exposes so far: its photos per local day, and
 * whether that is all of them (every stream done). The partial rail's input (F2). */
data class LoadedRows(val histogram: TimelineHistogram, val complete: Boolean)

/** Where the merged view of a selection stops (plan "Data path"). */
internal sealed interface Watermark {
    /** A selected stream has no page yet: nothing is exposed. */
    data object Blocked : Watermark

    /** Every row at or above [key] is complete across the selection. */
    data class At(val key: TimelineKey) : Watermark

    /** Every selected stream is done: no limit. */
    data object None : Watermark
}

private val KEY_ORDER = compareBy<TimelineKey>({ it.takenAt }, { it.photoId })

/** The watermark of [keys] given their stream rows: the newest `oldestFetched` among the
 * unfinished ones. */
internal fun watermarkOf(
    keys: Collection<String>,
    streams: List<FacetStreamEntity>,
): Watermark {
    val byKey = streams.associateBy { it.streamKey }
    var highest: TimelineKey? = null
    for (k in keys) {
        val s = byKey[k] ?: return Watermark.Blocked
        if (s.done) continue
        val oldest = s.oldestTakenAt?.let { TimelineKey(it, s.oldestPhotoId.orEmpty()) } ?: return Watermark.Blocked
        if (highest == null || KEY_ORDER.compare(oldest, highest) > 0) highest = oldest
    }
    return highest?.let { Watermark.At(it) } ?: Watermark.None
}

/**
 * Timeline place filter step 2: the filtered timeline's data (plan F1, F12, F13).
 *
 * A selection is a list of [FacetStreamSpec]s, one per maximal ticked place. Each stream's
 * pages are cached in Room (`facet_streams` + `facet_stream_rows`) for the **session**:
 * reselecting a subset fetches nothing, a superset only the new streams. The cache is
 * emptied by [clear] (the filter was cleared) and on the first [pager] of the process (a
 * cold start), since there's no per-facet `ETag` to revalidate against (F13). The
 * `photos` table and the unfiltered timeline's state are never touched (F10).
 *
 * **Fetches** run in this repository's own [scope], one per stream at a time, at most
 * [MAX_FACET_FETCHES_IN_FLIGHT] at once. A new selection ([pager]) cancels the fetches of
 * streams it dropped, and lets those it kept finish, since they fill the shared cache (F6).
 */
@Singleton
class PlaceFilterRepository internal constructor(
    private val db: AppDatabase,
    private val fetcher: FacetPageFetcher,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        db: AppDatabase,
        instanceStore: InstanceStore,
        archivistApiFactory: ArchivistApiFactory,
    ) : this(db, ApiFacetPageFetcher(instanceStore, archivistApiFactory), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val dao = db.facetStreamDao()
    private val permits = Semaphore(MAX_FACET_FETCHES_IN_FLIGHT)
    private val lock = Any()
    private val inFlight = HashMap<String, Deferred<Unit>>()

    /** The cache wipe [beginClear] started, until it has run. Every fetch and pager
     * started after it waits for it (guarded by [lock]). */
    private var pendingClear: Job? = null
    private var sessionStarted = false

    /** A filtered pager has been built since the last clear: the filtered timeline is on
     * screen, and the unfiltered one is hidden, its position to be restored exactly (F10). */
    @Volatile private var filterSession = false

    /** Whether a filter session is on (see [filterSession]). An edit made from the filtered
     * detail must not move the hidden unfiltered timeline ([writeEditedPhoto]). */
    val filterSessionActive: Boolean get() = filterSession

    /**
     * The filtered timeline for [streams], newest first, starting at [initialKey] (a
     * local jump's landing; null for the newest photo, which every change of filter
     * starts at, F10). Collecting it first cancels in-flight fetches for streams not in
     * [streams] ([retain]).
     *
     * Paged by [TimelinePagingSource] over [MergedPageQueries] with its own
     * [TimelineJumpCoordinator], so it shares nothing with the unfiltered pager.
     */
    fun pager(
        streams: List<FacetStreamSpec>,
        initialKey: TimelineKey? = null,
    ): Flow<PagingData<PhotoEntity>> =
        flow {
            startSession()
            // A clear requested before this pager (Back or ✕, then a new filter at once)
            // finishes first, so it can neither wipe this pager's first pages nor cancel
            // a fetch this pager joined.
            awaitPendingClear()
            filterSession = true
            val keys = streams.map { it.key }.toSet()
            retain(keys)
            val coordinator = TimelineJumpCoordinator()
            if (initialKey != null) {
                // A landing: the first load starts *at* it (index 0), as for the
                // timeline's jumps. Consumed at once: this pager is built with
                // initialKey already, so the one-shot must not fire on a later
                // invalidation and snap the grid back here.
                coordinator.stageLanding(initialKey)
                coordinator.consumeLanding()
            }
            emitAll(
                Pager(
                    config = PagingConfig(pageSize = FACET_STREAM_PAGE_SIZE, enablePlaceholders = false),
                    initialKey = initialKey,
                    remoteMediator = FacetMergeMediator(streams, this@PlaceFilterRepository),
                    pagingSourceFactory = { TimelinePagingSource(db, MergedPageQueries(keys), coordinator) },
                ).flow,
            )
        }

    /** Cancels in-flight fetches for streams not in [keys]. Those still selected keep
     * running and land in the cache. */
    internal fun retain(keys: Set<String>) {
        val dropped =
            synchronized(lock) {
                inFlight.filterKeys { it !in keys }.values.toList()
            }
        dropped.forEach { it.cancel() }
    }

    /**
     * Ends the filter session (F13): cancels every fetch **now**, and empties the stream
     * cache in [scope] once those fetches have stopped. Returns at once; `photos` is
     * untouched.
     *
     * Sequenced, not fire-and-forget: everything started after this call (a [fetchNext],
     * a [pager]) waits for the wipe to finish. Without that, a filter applied right after a
     * clear could join a fetch the pending clear then cancelled (the new grid's first load
     * failed, "Fetch superseded", until Retry), or have its fresh first pages deleted by
     * the wipe landing after them.
     */
    fun beginClear() {
        synchronized(lock) {
            filterSession = false
            val jobs = inFlight.values.toList().also { inFlight.clear() }
            jobs.forEach { it.cancel() }
            val previous = pendingClear
            pendingClear =
                scope.launch {
                    previous?.join()
                    // A fetch already writing its page finishes that write (Room's
                    // transaction isn't cancellable midway); the wipe comes after it.
                    jobs.forEach { it.join() }
                    db.useWriterConnection { transactor ->
                        transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                            dao.clearRows()
                            dao.clearStreams()
                        }
                    }
                }
        }
    }

    /** [beginClear], and waits for the cache to be empty. */
    suspend fun clear() {
        beginClear()
        awaitPendingClear()
    }

    private suspend fun awaitPendingClear() {
        synchronized(lock) { pendingClear }?.join()
    }

    /** The first selection of the process starts from an empty cache: whatever a previous
     * process left is from another session, possibly stale (F13). */
    private fun startSession() {
        synchronized(lock) {
            if (!sessionStarted) {
                sessionStarted = true
                beginClear()
            }
        }
    }

    /** The merged view's loaded photos per day for [streams], live (F2). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loaded(streams: List<FacetStreamSpec>): Flow<LoadedRows> {
        val keys = streams.map { it.key }.toSet()
        return dao.observeStreams(keys)
            .map { watermarkOf(keys, it) }
            .distinctUntilChanged()
            .flatMapLatest { w ->
                when (w) {
                    Watermark.Blocked -> flowOf(LoadedRows(TimelineHistogram(emptyMap(), 0), complete = false))
                    else -> {
                        val (t, p) = w.bounds()
                        dao.observeMergedDays(keys, t, p).map { days ->
                            LoadedRows(
                                TimelineHistogram(days.associate { it.day to it.n }, days.sumOf { it.n }),
                                complete = w == Watermark.None,
                            )
                        }
                    }
                }
            }
    }

    /**
     * The merged view's loaded photos for [streams], newest first and live: what the detail
     * pager swipes over when opened from the filtered grid (step 7). It stops at the
     * watermark, exactly as the grid does, and grows as pages land ([loadOlder]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadedPhotos(streams: List<FacetStreamSpec>): Flow<List<PhotoEntity>> {
        val keys = streams.map { it.key }.toSet()
        if (keys.isEmpty()) return flowOf(emptyList())
        return dao.observeStreams(keys)
            .map { watermarkOf(keys, it) }
            .distinctUntilChanged()
            .flatMapLatest { w ->
                when (w) {
                    Watermark.Blocked -> flowOf(emptyList())
                    else -> {
                        val (t, p) = w.bounds()
                        dao.observeMerged(keys, t, p)
                    }
                }
            }
    }

    /**
     * An edit made on this device (date, rotate, repair; F13): [photo], as the server now
     * has it, replaces the display columns of every cached row of that photo, `localDay`
     * recomputed. The place columns stay the stream's (F12). A date change can move the
     * row below the watermark, where it waits, like any other row, for its page.
     */
    suspend fun patchPhoto(photo: PhotoEntity) {
        dao.patchDisplay(
            photoId = photo.photoId,
            takenAt = photo.takenAt,
            tzOffsetMin = photo.tzOffsetMin,
            mime = photo.mime,
            width = photo.width,
            height = photo.height,
            status = photo.status,
            thumbs = photo.thumbs,
            encDek = photo.encDek,
            encKeyId = photo.encKeyId,
            localDay = photo.localDate().toString(),
        )
    }

    /**
     * "Update location" on this device (F13). A row whose stream the new [place] still
     * falls in keeps its place columns (F12). A row whose stream it has left **stays in
     * the cache** for the rest of the filter session, like any change the session can't
     * see (F13), but shows the photo's real place now (flattened as `photos` does: the
     * town, else the region). Removing it would pull the photo out from under the detail
     * pager the moment the update succeeds; setting the filter again drops it.
     */
    suspend fun patchPlace(
        photoId: String,
        place: PlaceDto?,
    ) {
        val level = place?.locality ?: place?.region
        for (row in dao.rowsOfPhoto(photoId)) {
            if (place != null && streamContains(row.streamKey, place)) continue
            dao.setRowPlace(row.streamKey, photoId, place?.cc, level?.name, level?.id)
        }
    }

    /** A photo deleted (trashed) on this device leaves every stream at once (F13). */
    suspend fun removePhoto(photoId: String) {
        dao.deleteByPhotoId(photoId)
    }

    /**
     * A local jump (F2, F10): the newest loaded photo on [day] or before it, else the
     * oldest loaded photo (the grid holds at the end of what's loaded). Never touches the
     * network or `photos`. Null for [day] null (the top) or when nothing is loaded.
     */
    suspend fun localLanding(
        streams: List<FacetStreamSpec>,
        day: LocalDate?,
    ): TimelineKey? {
        if (day == null) return null
        val keys = streams.map { it.key }.toSet()
        val w = watermark(keys)
        if (w == Watermark.Blocked) return null
        val (t, p) = w.bounds()
        val landing = dao.mergedLandingOnOrBefore(keys, t, p, day.toString()) ?: dao.mergedOldest(keys, t, p)
        return landing?.let { TimelineKey(it.takenAt, it.photoId) }
    }

    internal suspend fun watermark(keys: Collection<String>): Watermark = watermarkOf(keys, dao.streams(keys))

    /** How many photos the merged view of [keys] exposes now. */
    internal suspend fun exposedCount(keys: Collection<String>): Int {
        val w = watermark(keys)
        if (w == Watermark.Blocked) return 0
        val (t, p) = w.bounds()
        return dao.mergedCount(keys, t, p)
    }

    /**
     * One more page at the bottom of [streams]'s merged view, for the partial rail held in
     * its unloaded segment (F2): the same fetch an APPEND makes, still one page at a time
     * and only on demand. True if the view gained photos; false for nothing more to load,
     * or a failure (the caller tries again later, as a scroll would).
     */
    suspend fun loadOlder(streams: List<FacetStreamSpec>): Boolean {
        val keys = streams.map { it.key }
        val before = exposedCount(keys)
        return when (loadAtWatermark(streams)) {
            is MediatorResult.Success -> exposedCount(keys) > before
            else -> false
        }
    }

    /** [FacetMergeMediator]'s REFRESH: first pages only, for the streams that have none. */
    internal suspend fun loadFirstPages(streams: List<FacetStreamSpec>): MediatorResult =
        mediatorCall {
            val keys = streams.map { it.key }
            val have = dao.streams(keys).associateBy { it.streamKey }
            streams
                .filter { s -> have[s.key].let { it == null || (!it.done && it.oldestTakenAt == null) } }
                .map { fetchNext(it) }
                .awaitAll()
            MediatorResult.Success(endOfPaginationReached = watermark(keys) == Watermark.None)
        }

    /** [FacetMergeMediator]'s APPEND: the next page of the stream(s) holding the
     * watermark, repeated (boundedly) until the merged view exposes another photo. */
    internal suspend fun loadAtWatermark(streams: List<FacetStreamSpec>): MediatorResult =
        mediatorCall {
            val keys = streams.map { it.key }
            val before = exposedCount(keys)
            repeat(MAX_APPEND_ROUNDS) {
                val states = dao.streams(keys).associateBy { it.streamKey }
                val unfinished = streams.filter { states[it.key]?.done != true }
                if (unfinished.isEmpty()) return@mediatorCall MediatorResult.Success(endOfPaginationReached = true)
                val blocking = unfinished.filter { states[it.key]?.oldestTakenAt == null }
                val holders =
                    blocking.ifEmpty {
                        val oldest = unfinished.associateWith { s -> states.getValue(s.key).let { TimelineKey(it.oldestTakenAt!!, it.oldestPhotoId.orEmpty()) } }
                        val mark = oldest.values.maxWith(KEY_ORDER)
                        unfinished.filter { oldest.getValue(it) == mark }
                    }
                holders.map { fetchNext(it) }.awaitAll()
                val done = watermark(keys) == Watermark.None
                if (done || exposedCount(keys) > before) return@mediatorCall MediatorResult.Success(endOfPaginationReached = done)
            }
            MediatorResult.Success(endOfPaginationReached = false)
        }

    /** The one in-flight fetch of [spec]'s next page, started if there's none. Runs in
     * [scope], not the caller's: see the class doc. */
    internal fun fetchNext(spec: FacetStreamSpec): Deferred<Unit> =
        synchronized(lock) {
            inFlight[spec.key]?.takeIf { it.isActive }?.let { return it }
            // Started after a clear: runs only once the cache is empty (see beginClear).
            val barrier = pendingClear?.takeIf { it.isActive }
            val job =
                scope.async {
                    barrier?.join()
                    permits.withPermit { fetchPage(spec) }
                }
            inFlight[spec.key] = job
            job.invokeOnCompletion {
                synchronized(lock) { if (inFlight[spec.key] === job) inFlight.remove(spec.key) }
            }
            job
        }

    private suspend fun fetchPage(spec: FacetStreamSpec) {
        val stream = dao.stream(spec.key)
        if (stream?.done == true) return
        val page = fetcher.fetch(spec.facetType, spec.value, stream?.cursor, FACET_STREAM_PAGE_SIZE)
        val rows = page.items.map { it.toRow(spec) }
        val oldest = rows.minWithOrNull(compareBy({ it.takenAt }, { it.photoId }))
        db.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                dao.upsertRows(rows)
                dao.upsertStream(
                    FacetStreamEntity(
                        streamKey = spec.key,
                        cursor = page.cursor,
                        oldestTakenAt = oldest?.takenAt ?: stream?.oldestTakenAt,
                        oldestPhotoId = oldest?.photoId ?: stream?.oldestPhotoId,
                        done = page.cursor == null,
                        fetchedAt = ISO_MILLIS_UTC.format(Instant.now()),
                    ),
                )
            }
        }
    }

    /** Network and body failures become [MediatorResult.Error], so the grid shows its
     * error/Retry state; the stored cursor is untouched, so a retry resumes there. A fetch
     * cancelled because a newer selection dropped its stream is an error too, unless this
     * call is itself being cancelled. */
    private suspend fun mediatorCall(block: suspend () -> MediatorResult): MediatorResult =
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            MediatorResult.Error(IOException("Fetch superseded", e))
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        } catch (e: IllegalArgumentException) {
            // An unexpected body (SerializationException is one), or a status
            // AssetStatus.valueOf doesn't know: an error state, not an app crash.
            MediatorResult.Error(e)
        }

    /** [TimelinePagingSource]'s queries over the merged view of [keys], cut at the
     * watermark, which is re-read on every load (a write to either table invalidates the
     * source, so a generation never outlives a watermark change). Blocked: no rows. */
    internal inner class MergedPageQueries(
        private val keys: Set<String>,
    ) : KeysetPageQueries {
        override val tables = arrayOf("facet_stream_rows", "facet_streams")

        private suspend fun <T> withBounds(block: suspend (String?, String?) -> List<T>): List<T> {
            val w = watermark(keys)
            if (w == Watermark.Blocked) return emptyList()
            val (t, p) = w.bounds()
            return block(t, p)
        }

        override suspend fun pageFromKey(
            takenAt: String,
            photoId: String,
            limit: Int,
        ) = withBounds { t, p -> dao.mergedFromKey(keys, t, p, takenAt, photoId, limit) }

        override suspend fun pageFromStart(limit: Int) = withBounds { t, p -> dao.mergedFromStart(keys, t, p, limit) }

        override suspend fun pageAfter(
            takenAt: String,
            photoId: String,
            limit: Int,
        ) = withBounds { t, p -> dao.mergedAfter(keys, t, p, takenAt, photoId, limit) }

        override suspend fun pageBefore(
            takenAt: String,
            photoId: String,
            limit: Int,
        ) = withBounds { t, p -> dao.mergedBefore(keys, t, p, takenAt, photoId, limit) }
    }
}

/** The SQL bound parameters for a non-blocked watermark: (takenAt, photoId), or both null
 * for no limit. */
private fun Watermark.bounds(): Pair<String?, String?> =
    when (this) {
        is Watermark.At -> key.takenAt to key.photoId
        else -> null to null
    }

/** Whether a photo at [place] belongs in the facet stream [streamKey]
 * (`<facetType>#<value>`): the same town, region or country. */
internal fun streamContains(
    streamKey: String,
    place: PlaceDto,
): Boolean {
    val type = streamKey.substringBefore('#')
    val value = streamKey.substringAfter('#')
    return when (type) {
        "PLACE" -> place.locality?.id == value
        "REGION" -> place.region?.id == value
        "COUNTRY" -> place.cc == value
        else -> false
    }
}

/**
 * Writes [photo], refetched from the server after an edit made on this device (date,
 * rotate, repair), everywhere it is cached (plan step 7, F13):
 *
 * * `photos`, **only if the timeline window already holds it**. A photo opened from the
 *   filtered grid can be far outside the window, and inserting it there would leave an
 *   isolated row the window's bookkeeping doesn't account for. Opened from the timeline,
 *   it is always there, so that path is unchanged.
 * * The refresh key for the timeline's pager ([TimelineJumpCoordinator.stageExternalRefreshKey],
 *   staged before the write as before), **except during a filter session**: the
 *   unfiltered timeline is hidden then and must come back exactly where it was (F10),
 *   which its own last anchor already gives.
 * * The filter's cached stream rows ([PlaceFilterRepository.patchPhoto]).
 */
internal suspend fun writeEditedPhoto(
    photo: PhotoEntity,
    photoDao: PhotoDao,
    jumpCoordinator: TimelineJumpCoordinator,
    placeFilter: PlaceFilterRepository,
) {
    if (photoDao.getByPhotoId(photo.photoId) != null) {
        if (!placeFilter.filterSessionActive) jumpCoordinator.stageExternalRefreshKey(TimelineKey(photo.takenAt, photo.photoId))
        photoDao.upsertAll(listOf(photo))
    }
    placeFilter.patchPhoto(photo)
}

/** A facet entry as a cached stream row, with [spec]'s place (F12). */
internal fun FacetEntryDto.toRow(spec: FacetStreamSpec): FacetStreamRowEntity {
    val e = toEntity()
    return FacetStreamRowEntity(
        streamKey = spec.key,
        photoId = e.photoId,
        takenAt = e.takenAt,
        tzOffsetMin = e.tzOffsetMin,
        mime = e.mime,
        width = e.width,
        height = e.height,
        status = e.status,
        thumbs = e.thumbs,
        encDek = e.encDek,
        encKeyId = e.encKeyId,
        placeCc = spec.cc,
        placeName = spec.placeName,
        placeKey = spec.placeKey,
        specificity = spec.specificity,
        localDay = e.localDate().toString(),
    )
}

internal class ApiFacetPageFetcher(
    private val instanceStore: InstanceStore,
    private val archivistApiFactory: ArchivistApiFactory,
) : FacetPageFetcher {
    override suspend fun fetch(
        facetType: String,
        value: String,
        cursor: String?,
        limit: Int,
    ): FacetPageResponse {
        val instance = instanceStore.current.first() ?: throw IOException("No instance connected")
        val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
        return api.getFacetPage(facetUrl(instance.document.apiBase, facetType, value), cursor = cursor, limit = limit)
    }
}
