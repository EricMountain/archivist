package fr.enry.archivist.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.repo.FacetStreamSpec
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.data.repo.PhotoRepository
import fr.enry.archivist.data.repo.PlaceFilterRepository
import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import fr.enry.archivist.data.repo.UploadEvents
import fr.enry.archivist.ui.search.PlaceFilter
import fr.enry.archivist.ui.search.SelectionState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** The longest a cold start holds the grid back waiting to reseed at the present — see
 * `TimelineViewModel.startupSettled`. */
private const val STARTUP_RESEED_WAIT_MS = 4000L

/** How long the overlay's selection has to stay unchanged before it becomes the active
 * filter (timeline place filter F6). A proposal, to be tuned on the device in step 4. */
internal const val FILTER_DEBOUNCE_MS = 350L

/** Which grid a jump belongs to: the unfiltered timeline, or the place filter's. */
internal enum class TimelineMode { UNFILTERED, FILTERED }

/** A jump that has landed: [landing] is the key the grid should hold on, [position] the
 * rail position that asked for it (so the grid can land partway through the day, not on
 * its first photo), and [scrub] whether it was a mid-drag scrub — whose placement
 * `TimelineScrollbar` handles itself — rather than a release or programmatic jump.
 * [mode] says which grid it is for: a filtered landing must never move the unfiltered
 * grid, which keeps its position for when the filter is cleared (F10). */
internal data class JumpLanding(
    val landing: TimelineKey?,
    val position: RailPosition,
    val scrub: Boolean,
    val mode: TimelineMode = TimelineMode.UNFILTERED,
)

/**
 * The filtered rail's inputs (F2): [total] is N, the exact photo count of the selection
 * from the vocabulary (or the loaded count once everything is loaded, or if the vocabulary
 * undercounts), and [loaded] the photos loaded so far per local day. [complete]: every
 * selected stream is done, so [loaded] is the whole selection.
 */
data class FilteredRail(
    val total: Int,
    val loaded: TimelineHistogram,
    val complete: Boolean,
)

/**
 * Plan step 2.11. [locked] gates the whole screen off the grid the moment the master
 * key disappears — sign-out, delete-account, or (rare) never having unlocked yet in
 * this process; ordinary backgrounding no longer clears it, see
 * `ArchivistApplication`'s own doc — per "Locked state" in android.md: the timeline is
 * metadata and renders fine with no key at all, so without this the app would look
 * healthy while every thumbnail failed to decrypt. This is also what actually fixes the
 * staleness plan step 2.5's own STATUS.md note originally flagged against a one-shot
 * `unlocked` boolean in `MainActivity` that never re-checked [MasterKeyHolder] after the
 * first unlock (since removed there entirely — `MainActivity` now defers to this
 * [locked] StateFlow for the whole signed-in experience, not a local flag of its own) —
 * see [TimelineScreen]'s own `locked` branch for how it forces
 * [fr.enry.archivist.ui.onboarding.EnrolmentViewModel] to re-check rather than trusting
 * a possibly-stale cached instance.
 */
@HiltViewModel
class TimelineViewModel
    @Inject
    constructor(
        private val photoRepository: PhotoRepository,
        private val placeFilterRepository: PlaceFilterRepository,
        masterKeyHolder: MasterKeyHolder,
        instanceStore: InstanceStore,
        uploadEvents: UploadEvents,
    ) : ViewModel() {
        val locked: StateFlow<Boolean> =
            masterKeyHolder.current
                .map { it == null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), masterKeyHolder.current.value == null)

        /** The CDN host thumbnail URLs are built against — `null` only in the
         * impossible-in-practice case of reaching this screen with no connected
         * instance, in which case the grid just shows placeholders. */
        val cdnHost: StateFlow<String?> =
            instanceStore.current
                .map { it?.host }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /**
         * Bumped by a completed jump, which rebuilds the `Pager` rather than letting the
         * existing one carry on over a cache it no longer describes.
         *
         * `RemoteMediator`'s `endOfPaginationReached` is latched by Paging per direction
         * and cleared only when *Paging itself* runs a mediator `REFRESH`. A jump calls
         * [fr.enry.archivist.data.repo.TimelineRemoteMediator.reseedAt] directly (going
         * through `LazyPagingItems.refresh()` instead is what made the first version of
         * this feature bounce through several generations before settling), so that latch
         * survived the jump. Once `PREPEND` had legitimately reported "nothing newer than
         * the cache" at the present, a jump into the past left the flag set over a window
         * that now had eight months of newer photos above it — the mediator was never
         * asked for them again, and scrolling toward the present stopped dead as soon as
         * the locally cached rows ran out. Reported live as "after a few swipes at most it
         * blocks and won't go further into the future".
         *
         * A jump replaces the entire cache, so every piece of pager state derived from it
         * is stale by definition; rebuilding is the honest response and resets both
         * directions at once. It costs nothing visible: the reseed has already committed
         * the new window, and a fresh `Pager` starts at the top of it — which *is* the
         * landing photo.
         */
        private val pagerGeneration = MutableStateFlow(PagerSeed(generation = 0, initialKey = null))

        /** A rebuild, and where the rebuilt pager starts. */
        private data class PagerSeed(val generation: Int, val initialKey: TimelineKey?)

        /** What the *live* pager is currently anchored at, so a jump landing on exactly
         * that key again can skip rebuilding it — see [applyJump]. `null` is a real value
         * here (the present, `pagerGeneration`'s own starting state), not "unknown". */
        private var livePagerKey: TimelineKey? = null

        /**
         * False while a cold start is still moving a cache left in the past back to the
         * present — see `init`. No pager is built until then: one built over the old window
         * showed the last session's position first and then moved to the top, in a couple
         * of visible steps, once the reseed landed. The screen shows its loading spinner
         * meanwhile, the same as for a first load.
         */
        private val startupSettled = MutableStateFlow(false)

        @OptIn(ExperimentalCoroutinesApi::class)
        val timeline: Flow<PagingData<PhotoEntity>> =
            flow {
                startupSettled.first { it }
                emitAll(pagerGeneration)
            }.flatMapLatest { photoRepository.timeline(it.initialKey) }
                .cachedIn(viewModelScope)

        /** The fast-scroll range — `null` until the first successful fetch, or if it
         * keeps failing; [TimelineScrollbar] falls back to hiding/disabling fast-scroll
         * in that case rather than blocking the grid on it. */
        private val _bounds = MutableStateFlow<TimelineBounds?>(null)
        val bounds: StateFlow<TimelineBounds?> = _bounds.asStateFlow()

        /** Photos per local day, from Room — so the scrollbar is density-weighted the
         * instant a long press opens it, not after a round trip. Kept fresh by
         * [refreshBounds], which revalidates it alongside the range. */
        val histogram: StateFlow<TimelineHistogram?> =
            photoRepository.histogram().stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Emitted once a jump's new window is committed to Room, carrying the landing
         * key the grid should hold on — see `TimelineScreen`'s own doc for why this needs
         * to be the actual key, not inferred later from whatever `items.peek(0)` happens
         * to show by the time the collector runs: `PREPEND` (`loadNewerThanCache`) can
         * already be mid-burst, autonomously inserting newer content ahead of the landing,
         * by the time that read happens, which silently substitutes the wrong baseline.
         * The window itself starts at the requested instant (see
         * [TimelineJumpCoordinator]), so the grid only has to go to the top. A
         * `SharedFlow` with no replay on purpose: it's a one-shot "this just happened",
         * and a replayed value would re-scroll the grid on the next recomposition that
         * happens to re-collect it. */
        private val _jumpCompleted = MutableSharedFlow<JumpLanding>()
        internal val jumpCompleted: SharedFlow<JumpLanding> = _jumpCompleted.asSharedFlow()

        /** Jumps run one at a time. A drag issues a stream of scrubs and then a commit,
         * and two reseeds overlapping would leave the cache on whichever finished last
         * rather than on the one the user actually asked for. */
        private val jumpLock = Mutex()

        /**
         * The finger is over [day] mid-drag: move the grid there without ending the
         * gesture.
         *
         * Suspends until the window is in place, which is the point — the caller
         * `conflate`s on it, so this returning is what releases the next scrub, and the
         * drag is paced by however long that actually takes. Free when the day is already
         * cached; one screenful-sized request when it isn't.
         */
        suspend fun onScrubTo(day: LocalDate?) {
            // The mode is the one the rail was drawn for: read now, not when the jump runs.
            if (_activeFilter.value != null) {
                applyFilteredJump(day, scrub = true, RailPosition(day))
            } else {
                applyJump(day, scrub = true)
            }
        }

        /**
         * The drag was released on [day] (null for the top of the rail, "back to the
         * present"). Usually free: the scrubs have already loaded this window, so the
         * repository answers from Room.
         */
        internal fun onJumpCommitted(position: RailPosition) {
            // Decided at the release, not when the launched jump runs: a filtered rail's
            // release that runs after the filter was cleared must not move the unfiltered
            // grid (applyFilteredJump then finds no filter and does nothing).
            val filtered = _activeFilter.value != null
            viewModelScope.launch {
                if (filtered) {
                    applyFilteredJump(position.day, scrub = false, position)
                } else {
                    applyJump(position.day, scrub = false, position)
                }
            }
        }

        /** Failures are swallowed the same way every other network path on this screen
         * does: the cache keeps serving whatever it already had, and the grid doesn't
         * move. */
        private suspend fun applyJump(
            day: LocalDate?,
            scrub: Boolean,
            position: RailPosition = RailPosition(day),
        ) = jumpLock.withLock {
            val outcome =
                try {
                    photoRepository.jumpTo(day, scrub)
                } catch (e: IOException) {
                    null
                } catch (e: HttpException) {
                    null
                } ?: return@withLock

            // Rebuilt only when the landing actually differs from what's already live —
            // skipped entirely for a release that lands exactly where the last scrub
            // already put things, which used to force a visible reload of content that
            // was already on screen (reported live: "when I take my finger off the rail,
            // the screen refreshes even though it stays in the same position").
            //
            // Safe to skip precisely because nothing has moved: a `RemoteMediator`'s own
            // load state (including PREPEND's latched end-of-pagination — see
            // [pagerGeneration]'s own doc) survives a `PagingSource` restart *within* the
            // same `Pager`, which is exactly what an unchanged landing means happened, if
            // anything — Room's own invalidation already re-anchors the existing pager on
            // a staged landing with no rebuild needed at all. It only needs resetting when
            // the window has actually moved to somewhere with different headroom above
            // it, which an unchanged landing key rules out by definition.
            if (outcome.landing != livePagerKey) {
                livePagerKey = outcome.landing
                pagerGeneration.update { PagerSeed(it.generation + 1, outcome.landing) }
            }
            _jumpCompleted.emit(JumpLanding(outcome.landing, position, scrub, TimelineMode.UNFILTERED))
        }

        // ---- Place filter mode (timeline place filter step 3) ----
        //
        // The overlay reports its whole selection on every keystroke and tick
        // (onSelectionChanged). After FILTER_DEBOUNCE_MS of no change, and if the state is
        // liveApplicable (F6), it becomes the active filter, which rebuilds the filtered
        // pager through flatMapLatest; the superseded one is cancelled, and with it the
        // fetches for streams the new selection dropped (PlaceFilterRepository.retain).
        // Nothing here touches pagerGeneration, the unfiltered pager or `photos` (F10):
        // clearing the filter shows the unfiltered grid exactly where it was.

        /** The overlay's latest selection, before the debounce. */
        private val selection = MutableStateFlow<SelectionState?>(null)

        private val _activeFilter = MutableStateFlow<PlaceFilter?>(null)

        /**
         * The applied filter, or null for the unfiltered timeline. A filter with no places
         * ([PlaceFilter.isEmptySelection]: the query matched nothing, or everything was
         * unticked) is still a filter: the filtered grid is empty (coordinator amendment,
         * 2026-11-17), never a fallback to everything.
         */
        val activeFilter: StateFlow<PlaceFilter?> = _activeFilter.asStateFlow()

        /** One build of the filtered pager: its streams, where it starts (a filtered jump's
         * landing, else the newest photo) and a generation so the same key can rebuild. */
        private data class FilteredSeed(
            val streams: List<FacetStreamSpec>,
            val initialKey: TimelineKey?,
            val generation: Int,
        )

        private val filteredSeed = MutableStateFlow<FilteredSeed?>(null)

        /** Never reset, not even by a clear: a filter re-applied right after a clear must
         * be a new seed. Restarting at 1 made it *equal* to the seed before the clear, so
         * the StateFlow conflated the null away and the old pager (whose fetch the clear
         * had cancelled) stayed up, in its error state. */
        private var filteredGenerations = 0

        /**
         * The filtered grid's pages (empty when there's no filter or the filter has no
         * places). Rebuilt when the selection's streams change (F10: at the newest photo)
         * or on a filtered jump (at its landing); a change of query alone that selects the
         * same streams keeps the pager and its position.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val filteredTimeline: Flow<PagingData<PhotoEntity>> =
            filteredSeed
                .flatMapLatest { seed ->
                    if (seed == null || seed.streams.isEmpty()) {
                        flowOf(PagingData.empty())
                    } else {
                        placeFilterRepository.pager(seed.streams, seed.initialKey)
                    }
                }.cachedIn(viewModelScope)

        /** The partial rail's inputs (F2), or null with no filter. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val filteredRail: StateFlow<FilteredRail?> =
            filteredSeed
                .map { it?.streams }
                .distinctUntilChanged()
                .flatMapLatest { streams ->
                    when {
                        streams == null -> flowOf(null)
                        streams.isEmpty() -> flowOf(FilteredRail(0, TimelineHistogram(emptyMap(), 0), complete = true))
                        else ->
                            combine(placeFilterRepository.loaded(streams), _activeFilter) { loaded, filter ->
                                val n = filter?.photoCount ?: 0
                                FilteredRail(
                                    total = if (loaded.complete) loaded.histogram.total else maxOf(n, loaded.histogram.total),
                                    loaded = loaded.histogram,
                                    complete = loaded.complete,
                                )
                            }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** The overlay's selection changed (a keystroke, a tick, All/None). Applies after
         * [FILTER_DEBOUNCE_MS] of quiet, if [SelectionState.liveApplicable]; below the
         * 2-character floor the previous filter stands. */
        fun onSelectionChanged(state: SelectionState) {
            selection.value = state
        }

        /** The IME Search action: applies the current selection at once, whatever its
         * length (F6). */
        fun applyNow() {
            selection.value?.let { setActiveFilter(it.toFilter()) }
        }

        /** ✕ on the chip, or Back on a filtered timeline (F8, F11): no filter, and the
         * session's stream cache is emptied (F13). The unfiltered grid is untouched, so it
         * reappears exactly where it was (F10). */
        fun clearFilter() {
            selection.value = null
            setActiveFilter(null)
            // Synchronous on purpose: the cancel happens now, and a filter applied right
            // after this waits for the wipe (PlaceFilterRepository.beginClear).
            placeFilterRepository.beginClear()
        }

        private fun setActiveFilter(filter: PlaceFilter?) {
            _activeFilter.value = filter
            if (filter == null) {
                filteredSeed.value = null
                return
            }
            val current = filteredSeed.value
            if (current == null || current.streams.map { it.key }.toSet() != filter.streamKeys) {
                filteredSeed.value = FilteredSeed(filter.streams, initialKey = null, generation = ++filteredGenerations)
            }
        }

        /** The partial rail is held in its unloaded segment (F2): load one more page of
         * the filter. True if anything loaded. */
        suspend fun loadOlderFiltered(): Boolean {
            val seed = filteredSeed.value ?: return false
            if (seed.streams.isEmpty()) return false
            return placeFilterRepository.loadOlder(seed.streams)
        }

        /** A rail jump in filter mode (F2, F10): answered in Room by
         * [PlaceFilterRepository.localLanding], never by [PhotoRepository.jumpTo], so the
         * unfiltered cache and pager stay as they were. */
        private suspend fun applyFilteredJump(
            day: LocalDate?,
            scrub: Boolean,
            position: RailPosition,
        ) = jumpLock.withLock {
            val seed = filteredSeed.value ?: return@withLock
            if (seed.streams.isEmpty()) return@withLock
            val landing = placeFilterRepository.localLanding(seed.streams, day)
            if (day != null && landing == null) return@withLock
            if (landing != seed.initialKey) {
                // Lost to a change of filter meanwhile: that rebuild wins, and this jump is moot.
                if (!filteredSeed.compareAndSet(seed, seed.copy(initialKey = landing, generation = filteredGenerations + 1))) return@withLock
                filteredGenerations++
            }
            _jumpCompleted.emit(JumpLanding(landing, position, scrub, TimelineMode.FILTERED))
        }

        private fun refreshBounds() {
            viewModelScope.launch {
                try {
                    photoRepository.fetchTimelineBounds()?.let { _bounds.value = it }
                } catch (e: IOException) {
                    // offline -- keep whatever range (possibly none) is already shown.
                } catch (e: HttpException) {
                    // server error -- same as above.
                }
            }
            viewModelScope.launch {
                try {
                    photoRepository.syncHistogram()
                } catch (e: IOException) {
                    // offline -- the cached histogram (or the time-scale fallback) stands.
                } catch (e: HttpException) {
                    // same as above.
                }
            }
        }

        init {
            @OptIn(FlowPreview::class)
            selection
                .debounce(FILTER_DEBOUNCE_MS)
                .onEach { state -> if (state != null && state.liveApplicable) setActiveFilter(state.toFilter()) }
                .launchIn(viewModelScope)
            refreshBounds()
            // A fresh start begins at the present. Room outlives the process, so the cache
            // still holds whatever window the last session ended in (often a fast-scroll
            // landing, with everything newer cleared), and a null-keyed pager starts at that
            // window's first row — reported as "the start position is random". An empty
            // cache is left alone: the mediator's own initial REFRESH already fetches the
            // newest page. Waits for unlock since the reseed needs the master key; offline
            // failures are swallowed by [applyJump], leaving the cache as it was.
            //
            // The grid waits for this (startupSettled) only when the cache is sitting in the
            // past: one that already reaches the present shows at once, and the reseed lands
            // on the same top row (checked live: no visible move). The wait is capped so a
            // slow network still shows the cached window rather than a spinner; in that case
            // the reseed moves the grid when it does land, as before.
            viewModelScope.launch {
                locked.first { !it }
                if (photoRepository.cacheStartsInPast()) {
                    launch {
                        delay(STARTUP_RESEED_WAIT_MS)
                        startupSettled.value = true
                    }
                } else {
                    startupSettled.value = true
                }
                if (photoRepository.hasCachedPhotos()) applyJump(null, scrub = false)
                startupSettled.value = true
            }
            // See UploadEvents' own doc for why the timeline needs this at all (unlike
            // the queue screen, which observes `upload_queue` directly): the upload
            // pipeline never writes to the `photos` table itself, so nothing else here
            // would ever notice a finished upload. PhotoRepository.refreshLatest's own
            // doc covers why this calls that -- a plain Room upsert -- rather than
            // `LazyPagingItems.refresh()`, which used to visibly reset scroll position
            // to the top on every upload. IOException/HttpException are swallowed: a
            // transient failure here just means this particular top-up is stale: the
            // next append or an explicit refresh resyncs regardless, same as
            // TimelineRemoteMediator's own offline-first behaviour.
            uploadEvents.completed
                .onEach {
                    try {
                        photoRepository.refreshLatest()
                    } catch (e: IOException) {
                        // offline -- see doc above.
                    } catch (e: HttpException) {
                        // server error -- see doc above.
                    }
                    // The newest end of the range may just have moved -- best-effort,
                    // same swallow-and-retry-next-time reasoning as refreshBounds itself.
                    refreshBounds()
                }.launchIn(viewModelScope)
        }
    }
