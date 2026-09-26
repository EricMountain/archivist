package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import fr.enry.archivist.crypto.EncryptedThumbRef
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import fr.enry.archivist.ui.detail.DetailScreen
import fr.enry.archivist.ui.onboarding.EnrolmentScreen
import fr.enry.archivist.ui.onboarding.EnrolmentUiState
import fr.enry.archivist.ui.onboarding.EnrolmentViewModel
import fr.enry.archivist.ui.onboarding.PermissionOnboardingScreen
import fr.enry.archivist.ui.settings.SettingsScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.runtime.snapshotFlow
import fr.enry.archivist.ui.preview.GRID_PREVIEW_SETTLE_MS
import fr.enry.archivist.ui.preview.VideoPreview
import fr.enry.archivist.ui.preview.previewRef
import fr.enry.archivist.ui.preview.selectPlayingIndices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The rung shown in the grid — matches [fr.enry.archivist.sync.Thumbnailer]'s smallest
 * rung, per android.md's "load the 256 thumbnail for instant paint". */
private const val GRID_THUMB_SIZE = 256

/** How long after a jump the `jumpCompleted` collector keeps reasserting the scroll
 * position at all. Bounded so it stops fighting the user's own scrolling once real
 * browsing resumes — an ordinary `APPEND` from scrolling also changes `itemCount`, and
 * that must not get snapped back to the top. Comfortably longer than a jump's own
 * settle time in practice (page loads observed finishing within a few hundred ms). */
private const val JUMP_SCROLL_SETTLE_WINDOW_MS = 2000L

/**
 * Plan step 2.11: the justified-grid timeline, Paging 3 over Room. Reuses
 * [EnrolmentScreen] wholesale for the locked state's "unlock action" (its own
 * `determineStep()` already tries a silent unlock first) rather than building a second
 * unlock ceremony — see [TimelineViewModel.locked]'s doc for why this screen is what
 * actually checks the master key continuously, rather than trusting a one-shot flag
 * from further up the tree.
 *
 * `MainActivity`'s `ArchivistApp` mounts this screen exactly once, right after sign-in,
 * and never unmounts it again for the rest of the process (see its own doc for why that
 * used to be split into a separate `unlocked` boolean gating a second, separately-
 * mounted `EnrolmentScreen`, and why that split was removed 2026-09-26): everything
 * from "signed in but not yet unlocked" through "unlocked and loaded" is this screen's
 * own concern from here on, including the `PermissionOnboardingScreen` gate further
 * down, not something the caller decides by remounting a different screen underneath.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun TimelineScreen(
    onSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()

    // hiltViewModel() here resolves to the *same* EnrolmentViewModel instance the
    // original sign-in flow created -- this app has no navigation library, so every
    // call site is keyed only by class name against MainActivity's own ViewModelStore
    // (see AGENTS.md's "hiltViewModel() ... resolves to the Activity's own
    // ViewModelStore" note, same bug class as DetailViewModel's dismissDelete()). Its
    // uiState can therefore still read Unlocked from *before* this lock, and init{}
    // won't rerun on a cached instance -- so this screen has to force a fresh
    // checkStep() itself rather than trust the stale state, or the app hangs on a
    // spinner forever with nothing left to re-check it. checkStep() is what actually
    // re-populates MasterKeyHolder; onUnlocked is deliberately a no-op here since
    // TimelineViewModel.locked flipping back to false (once checkStep() succeeds) is
    // what un-mounts the EnrolmentScreen delegation below on its own.
    //
    // Obtained -- and its state collected -- unconditionally now, not just while
    // locked, so the "Checking"/"Unlocked" sub-states can share the exact same spinner
    // call as the timeline's own loading state further down instead of each mounting
    // their own separate `CircularProgressIndicator`. See [enrolmentIsSpinning]'s own
    // doc for why that sharing is the actual point, not just a style choice.
    val enrolmentViewModel: EnrolmentViewModel = hiltViewModel()
    val enrolmentState by enrolmentViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) enrolmentViewModel.checkStep() }

    // `Checking` (a silent-unlock attempt in flight) and `Unlocked` (the one-frame
    // terminal state before `TimelineViewModel.locked` itself catches up and flips this
    // screen's own `locked` false) are the only two `EnrolmentUiState`s that render as a
    // bare spinner inside `EnrolmentScreen` -- every other one (recovery code entry, a
    // device-unlock prompt, a network error, ...) needs its own real screen and stays
    // delegated to it below. Folding just these two into this screen's own spinner
    // (further down) instead is what actually makes the unlock-then-load spinner
    // sequence read as one spinner: reported live 2026-09-26 that even after the two
    // were positioned identically (previous pass), they still looked like two distinct
    // spinners -- because they were two distinct `CircularProgressIndicator`
    // composables, each inside its own separately-mounted subtree (one in
    // `EnrolmentScreen`'s own composition, one in this screen's), so each restarted its
    // indeterminate rotation animation from scratch on mount, a visible jump in the
    // spinning arc even at the same screen position. Routing both phases through one
    // shared call site that's never unmounted between them keeps that animation running
    // continuously instead.
    val enrolmentIsSpinning = locked && (enrolmentState is EnrolmentUiState.Checking || enrolmentState is EnrolmentUiState.Unlocked)
    if (locked && !enrolmentIsSpinning) {
        EnrolmentScreen(onUnlocked = {}, modifier = modifier, viewModel = enrolmentViewModel)
        return
    }

    // Plan step 2.19's media/notification permission gate. Wrapped around this whole
    // remaining tail (rather than from `ArchivistApp`, which used to wrap this screen
    // from outside) so it applies to both the shared spinner right below and the real
    // grid further down, but *not* the `EnrolmentScreen` delegation just above -- a
    // real, interactive form (recovery code entry, device-unlock prompt, ...) still
    // never gets permission prompts stacked on top of it, matching plan step 2.19's own
    // "not any earlier" than actually needed. The one deliberate, narrow change from
    // before: permissions can now first be requested a little earlier than strict
    // confirmed-unlock -- as early as `enrolmentIsSpinning`'s brief, non-interactive
    // silent-unlock-attempt window -- rather than only once `TimelineViewModel.locked`
    // itself flips false. That's the direct cost of `PermissionOnboardingScreen` no
    // longer living at `ArchivistApp`'s own level: see this function's own top-of-file
    // doc and [enrolmentIsSpinning]'s for why keeping it there was the actual remaining
    // spinner-continuity gap, reported live 2026-09-26 a second time -- `ArchivistApp`
    // used to mount a *second*, separately-composed `EnrolmentScreen` (and, behind it,
    // its own `PermissionOnboardingScreen`) before ever reaching this screen at all, so
    // even a perfectly unified spinner in here still looked like two spinners next to
    // that separate mount. The early exits below are `return@PermissionOnboardingScreen`
    // rather than the bare `return`s they were before this wrapper existed -- a plain
    // `return` from inside this lambda would try to return from `TimelineScreen` itself,
    // which only compiles if `PermissionOnboardingScreen` is `inline`, and it isn't (no
    // need to be: a labelled return out of just this lambda has the identical effect,
    // since the wrapper call is already the last thing `TimelineScreen` does).
    PermissionOnboardingScreen(modifier = modifier) {
        // Collected unconditionally -- even while locked, during `enrolmentIsSpinning`
        // -- for the same reason: per `TimelineViewModel.locked`'s own doc, the lock
        // exists purely so thumbnails (which *do* need the master key) are never shown
        // undecryptable, not because metadata fetching needs it. Starting the fetch
        // immediately rather than waiting for unlock to resolve overlaps the two waits
        // instead of serialising them (metadata is often ready before the unlock
        // ceremony finishes), on top of being what lets the loading state below share
        // the spinner used during `enrolmentIsSpinning` in the first place.
        val items = viewModel.timeline.collectAsLazyPagingItems()
        val host by viewModel.cdnHost.collectAsStateWithLifecycle()
        val bounds by viewModel.bounds.collectAsStateWithLifecycle()
        val histogram by viewModel.histogram.collectAsStateWithLifecycle()

        // LazyPagingItems starts at NotLoading(false)/itemCount==0 -- the same shape a
        // verified-empty library has -- until the LaunchedEffect inside
        // collectAsLazyPagingItems actually begins collecting the Flow, so without this
        // the "No photos yet." text flashed on every cold start before the real load
        // began (see timelineContentState's own doc). Hoisted at this level, same
        // reasoning as gridState just below: a Settings or Detail round trip un-mounts
        // TimelineGrid, and a `remember` scoped there would forget a real Loading was
        // already observed and get stuck re-showing the spinner forever instead of
        // resolving to "No photos yet." once the genuinely-empty result already
        // arrived. One-way by construction: it only ever reads the current LoadState
        // and latches true, never resets to false.
        //
        // Reads `source`/`mediator` directly rather than the convenience `refresh`
        // field -- see timelineContentState's own doc for why `refresh` alone isn't
        // enough here either.
        var hasStartedLoading by remember { mutableStateOf(false) }
        items.loadState.let { state ->
            if (state.source.refresh is LoadState.Loading || state.mediator?.refresh is LoadState.Loading) {
                hasStartedLoading = true
            }
        }

        val loadState = items.loadState
        val contentState = timelineContentState(items.itemCount, loadState.source.refresh, loadState.mediator?.refresh, hasStartedLoading)

        // Rendered full-screen with no top bar, deliberately matching EnrolmentScreen's
        // own "Checking" spinner (same `modifier`, same plain full-size centering, same
        // shared call site as [enrolmentIsSpinning] above falls through to) rather than
        // nesting it below the 3-dot-menu Row the way TimelineGrid's other three states
        // are -- that Row eats real height, so a loading spinner rendered there used to
        // sit visibly lower than the unlock spinner shown right before it. Checked
        // here, before the DetailScreen/Settings branches below, for the same reason
        // `enrolmentIsSpinning` is checked before them further up: while either spinner
        // condition holds, this screen must never fall through to Detail/Settings even
        // if `selectedPhoto`/`showSettings` happen to still be `true` from before a
        // relock (both survive a lock cycle, since they're `remember`ed at this
        // composable's own top level, not reset by it).
        //
        // Reported live 2026-09-26 that even once the two spinners were positioned
        // identically (previous pass), they still read as two distinct spinners --
        // because they were, literally: two different `CircularProgressIndicator`
        // composables in two different subtrees, each restarting its indeterminate
        // rotation animation from scratch on mount. `enrolmentIsSpinning || contentState
        // == LOADING` is one shared boolean gating one shared call, so once true it
        // stays the same composition node across the whole locked-spinning ->
        // unlocked-loading transition -- no unmount, no animation restart, actually one
        // continuous spinner rather than two aligned ones. Narrowly safe to keep this
        // simple (no extra latch needed): [hasStartedLoading] only ever flips true once
        // per mount, so `contentState` can only be `LOADING` on its own (rather than via
        // `enrolmentIsSpinning`) once per lock cycle too -- not something that recurs
        // mid-browsing with the top bar already up.
        if (enrolmentIsSpinning || contentState == TimelineContentState.LOADING) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@PermissionOnboardingScreen
        }

        // Hoisted above the selectedPhotoId branch below (rather than left for
        // LazyVerticalGrid to create its own default one down in TimelineItemGrid) so
        // it survives a round trip through DetailScreen: a composable that leaves
        // composition entirely -- which TimelineGrid does whenever DetailScreen is
        // showing, since the `return` below skips over it -- has its own `remember`ed
        // state discarded and recreated from scratch next time, which without this
        // hoist reset scroll position to the top on every "open a photo, then back
        // out".
        val gridState = rememberLazyGridState()

        // A jumped-to window *starts* at the requested instant (TimelineJumpCoordinator), so
        // landing on it used to just mean "go to index 0" once the window was committed to
        // Room. That stopped being true once `TimelineRemoteMediator.loadNewerThanCache`
        // (PREPEND) is taken into account: it inserts newer content *ahead* of the landing by
        // design (so a jumped-to position can be scrolled back out of toward the present, per
        // that mediator's own doc), and — confirmed live, 2026-09-13 — it can start doing so
        // within a couple of hundred milliseconds of the landing committing, sometimes before
        // the very first `scrollToItem` here even runs. "Index 0" is a moving target once
        // that's happening; the landing's *own* identity isn't. [landingIndex] finds where
        // the landing photo currently sits (a bounded `peek`-only scan, never triggers a
        // load) and this scrolls there instead of blindly to 0 — so PREPEND growing the
        // window ahead of it no longer matters at all, rather than being raced against.
        //
        // Deliberately a one-shot event rather than a LaunchedEffect keyed on load state,
        // too: keying on items.itemCount re-ran this on every page that loaded afterwards,
        // yanking the grid back mid-scroll.
        LaunchedEffect(Unit) {
            viewModel.jumpCompleted.collect { landing ->
                gridState.scrollToItem(landingIndex(items, landing) ?: 0)
                // One reassert isn't enough: a jump's fresh PagingData generation streams in
                // over several subsequent page loads, not one shot, and *each* one can
                // retrigger LazyVerticalGrid's own key-based position-preservation, nudging
                // the scroll away from the landing again — confirmed live via instrumentation,
                // itemCount still growing (168 -> 257 -> 258) well after an earlier reassert
                // had already reported reaching the landing, with the position drifting again
                // on the next page. Reported as "Latest doesn't quite get me to the top... I
                // can't scroll to the top date label itself", and later (2026-09-13) as "the
                // timeline jumps off to a random place" the instant a rail drag is released.
                //
                // So this keeps reasserting on every single itemCount change — no debounce:
                // `PREPEND`'s own local (network-free) round trips land in ~30-90ms each, well
                // inside what a "wait for a lull" debounce would have waited out, so a
                // debounce meant letting a whole burst run before the very first check ever
                // got a chance to catch it — for a bounded window after the jump, rather than
                // trusting a single delayed retry. Bounded so it stops fighting the user's own
                // scrolling once real browsing resumes — an APPEND from an ordinary scroll
                // also changes itemCount, and this must not snap that back to the landing.
                //
                // Launched as its own coroutine, deliberately not awaited inline: this
                // collector is also what `_jumpCompleted.emit(...)` suspends on in
                // `TimelineViewModel.applyJump`, which runs under the same lock every scrub
                // in a drag shares. Awaiting the full window here serialised every later
                // scrub behind this one's own settle time, which — confirmed live — was
                // enough to stall a continuous drag almost completely; several seconds of a
                // finger sweeping across the rail rendered as the grid barely moving.
                // Reasserting to the same index from several overlapping launches at once is
                // harmless (they agree on the target), so nothing here needs the ordering
                // that awaiting would have provided anyway.
                //
                // `mapNotNull`/`distinctUntilChanged` rather than a `takeWhile`-guarded
                // `scrollToItem(0)`: the old version gave up reasserting entirely the instant
                // index 0 stopped being the landing, unable to tell "still catching up to my
                // own landing's settling pages" apart from "chasing content PREPEND is
                // autonomously adding" — since both changed itemCount and moved whatever was
                // at 0 identically. Tracking the landing's own index sidesteps the ambiguity
                // outright: there's nothing to give up on, since PREPEND changing the
                // landing's index *is* the correct new answer, not a signal to stop.
                launch {
                    withTimeoutOrNull(JUMP_SCROLL_SETTLE_WINDOW_MS) {
                        snapshotFlow { items.itemCount }
                            .mapNotNull { landingIndex(items, landing) }
                            .distinctUntilChanged()
                            .collect { gridState.scrollToItem(it) }
                    }
                }
            }
        }

        // Plan step 2.12: which photo the detail screen is open on, if any. Plain local
        // state, not a nav-library back stack -- this app has none yet (see MainActivity's
        // own note), same pattern every other screen transition here already uses.
        // The whole entity, not just its id: DetailScreen shows this photo's own thumbnail
        // while its swipe list catches up (see DetailScreen's initialPhoto doc).
        var selectedPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
        val openPhoto = selectedPhoto
        if (openPhoto != null) {
            // A repair's own effect on the grid (re-settling on the repaired photo) is
            // handled independently of this navigation -- see
            // TimelineJumpCoordinator.stageAndAnnounceLanding's own doc -- so this stays the
            // plain "close the screen" it always was, with no repair-awareness needed here.
            DetailScreen(initialPhoto = openPhoto, onBack = { selectedPhoto = null }, modifier = modifier)
            return@PermissionOnboardingScreen
        }

        // Plan step 2.14: Settings (which now also hosts Trash — see its own doc) is the
        // permanent entry point 2.13 deferred. Same "standalone screen, plain local
        // toggle" pattern as selectedPhotoId above.
        var showSettings by remember { mutableStateOf(false) }
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false }, onSessionEnded = onSessionEnded, modifier = modifier)
            return@PermissionOnboardingScreen
        }

        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // A 3-dot menu rather than a bare "Settings" button — for consistency with
                // DetailScreen's own top bar (plan step 2.12's repair/delete menu), even
                // though Settings is currently its only entry.
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showMenu = true }) { Text("⋮") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                showSettings = true
                            },
                        )
                    }
                }
            }
            TimelineGrid(
                items = items,
                host = host,
                gridState = gridState,
                bounds = bounds,
                histogram = histogram,
                contentState = contentState,
                onPhotoClick = { selectedPhoto = it },
                onScrub = viewModel::onScrubTo,
                onCommit = viewModel::onJumpCommitted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Where [landing] currently sits in [items] — a bounded [LazyPagingItems.peek] scan, so
 * it never triggers a page load. `null` for [landing] means "back to the present", which
 * always resolves to `0`, not a search (there's no photo identity to look for). A real
 * `landing` that isn't found at all (fell out of the loaded window entirely — shouldn't
 * happen in practice, but the caller must not misbehave if it does) also returns `null`,
 * distinct from index `0` on purpose.
 *
 * Exists because `TimelineRemoteMediator.loadNewerThanCache` (`PREPEND`) inserts newer
 * content *ahead* of the landing by design — see [jumpCompleted]'s own collector, which
 * scrolls to whatever this resolves to rather than hardcoding index `0`.
 */
private fun landingIndex(
    items: LazyPagingItems<TimelineItem>,
    landing: TimelineKey?,
): Int? {
    if (landing == null) return 0
    for (i in 0 until items.itemCount) {
        val item = items.peek(i)
        if (item is TimelineItem.Photo && item.photo.photoId == landing.photoId) return i
    }
    return null
}

/** What [TimelineGrid] should render for the current combination of item count, refresh
 * state, and whether a real load has ever actually started. Pulled out as a pure
 * function — mirrors [fr.enry.archivist.ui.timeline.TimelineScale]/`queueIdleReason`'s
 * own "testable decision table with no Compose in the loop" convention — because this
 * repo has no Compose UI test harness (see `TimelineViewModelTest`'s own gap note in
 * STATUS.md), so the branching itself has to be verifiable without one. */
internal enum class TimelineContentState { LOADING, ERROR, EMPTY, CONTENT }

/**
 * A brand-new library (nothing uploaded yet — the ordinary state right after signing in
 * on a fresh device, per this session's own live check against the `dev` instance's
 * DynamoDB table) looks identical to a stuck loading spinner or a silently-failed
 * `RemoteMediator` unless the three are told apart explicitly. `LazyPagingItems.loadState`
 * is the only signal that distinguishes "still loading page one" from "loaded, and
 * there's truly nothing" from "the fetch failed" — `itemCount == 0` alone can't.
 *
 * Two separate gaps, both reported live against a real cold start, neither fixed by
 * reading the convenience `CombinedLoadStates.refresh` field alone:
 *
 * 1. [LazyPagingItems] starts life at `NotLoading(false)` — the same shape a genuinely
 *    empty, already-resolved library has — for however long it takes the underlying
 *    `Flow<PagingData>` to actually start being collected (a `LaunchedEffect`, so at
 *    least one frame after first composition, longer if the ViewModel/Hilt graph is slow
 *    to spin up). [hasStartedLoading] gates this: it only ever flips true (never back),
 *    once a real `Loading` state has actually been observed on either [sourceRefresh] or
 *    [mediatorRefresh] — see [TimelineScreen]'s own latch for why it has to watch both.
 * 2. Even after that, `CombinedLoadStates.refresh`'s own KDoc admits it "generally defers
 *    to mediator if it exists" — key word *if*: `MutableCombinedLoadStateCollection`'s
 *    actual merge (`computeHelperState` in the Paging 3.5.1 sources) falls straight back
 *    to the *source*'s state whenever `mediator == null`, i.e. whenever the
 *    `RemoteMediator` hasn't reported anything for this `LoadType` yet at all — not
 *    merely "not loading". Room's own local query resolves fast (often to zero rows, on
 *    a device with nothing cached yet) before the `RemoteMediator`'s network `REFRESH`
 *    has been dispatched into that state, so the combined `refresh` genuinely reports
 *    `NotLoading` for a real stretch of wall-clock time — a live 401/network round trip,
 *    not a single frame — while the actual fetch is still in flight. Confirmed against
 *    Paging's own KDoc, which names exactly this: "for use cases that require reacting to
 *    LoadState of source and mediator specifically... LoadStates exposed via source and
 *    mediator should be used directly" instead of the convenience field. Fixed by doing
 *    exactly that: [sourceRefresh] and [mediatorRefresh] are read and checked
 *    independently, so a `Loading` `RemoteMediator` fetch keeps the spinner up even while
 *    Room's own `source.refresh` has already resolved to an empty `NotLoading`.
 */
internal fun timelineContentState(
    itemCount: Int,
    sourceRefresh: LoadState,
    mediatorRefresh: LoadState?,
    hasStartedLoading: Boolean,
): TimelineContentState =
    when {
        itemCount > 0 -> TimelineContentState.CONTENT
        sourceRefresh is LoadState.Loading || mediatorRefresh is LoadState.Loading || !hasStartedLoading ->
            TimelineContentState.LOADING
        sourceRefresh is LoadState.Error || mediatorRefresh is LoadState.Error -> TimelineContentState.ERROR
        else -> TimelineContentState.EMPTY
    }

@Composable
private fun TimelineGrid(
    items: LazyPagingItems<TimelineItem>,
    host: String?,
    gridState: LazyGridState,
    bounds: TimelineBounds?,
    histogram: TimelineHistogram?,
    // Computed once by the caller (TimelineScreen), not re-derived here: LOADING is
    // special-cased there to render full-screen with no top bar, before this composable
    // is even reached -- see that call site's own doc for why. Still handled below for
    // TimelineContentState's exhaustiveness and as a defensive fallback, not because
    // this branch is expected to run in practice.
    contentState: TimelineContentState,
    onPhotoClick: (PhotoEntity) -> Unit,
    onScrub: suspend (LocalDate?) -> Unit,
    onCommit: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (contentState) {
        TimelineContentState.LOADING ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        TimelineContentState.ERROR ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load your photos", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = items::retry) { Text("Retry") }
                }
            }

        TimelineContentState.EMPTY ->
            Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No photos yet. Back up a folder in Settings to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

        TimelineContentState.CONTENT ->
            Box(modifier.fillMaxSize()) {
                // The host view draws its own fading scroll indicator over any scrollable
                // content, which has nothing to do with (and doesn't agree with) the
                // time-based one below — Compose exposes no way to opt a single lazy
                // layout out of it, so it's turned off at the View that actually draws it.
                val view = LocalView.current
                LaunchedEffect(view) {
                    view.isVerticalScrollBarEnabled = false
                    view.isHorizontalScrollBarEnabled = false
                }
                TimelineScrollbar(
                    gridState = gridState,
                    items = items,
                    bounds = bounds,
                    histogram = histogram,
                    onScrub = onScrub,
                    onCommit = onCommit,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TimelineItemGrid(items, host, gridState, onPhotoClick, Modifier.fillMaxSize())
                }
            }
    }
}

@Composable
private fun TimelineItemGrid(
    items: LazyPagingItems<TimelineItem>,
    host: String?,
    gridState: LazyGridState,
    onPhotoClick: (PhotoEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which cells currently play their video preview (design.md, "Video preview clip").
    // Nothing plays while the grid is moving; once it has been still for
    // GRID_PREVIEW_SETTLE_MS, the first few visible cells that have a preview start, and
    // the moment it moves again they are all released. Keyed by photoId, not index: the
    // list can shift underneath as paging loads.
    var playingIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(gridState, items) {
        snapshotFlow { gridState.isScrollInProgress to items.itemCount }.collectLatest { (scrolling, _) ->
            if (scrolling) {
                playingIds = emptySet()
                return@collectLatest
            }
            delay(GRID_PREVIEW_SETTLE_MS)
            // Bounds-checked: after a rail commit rebuilds the pager the list can be shorter
            // than the grid's layoutInfo still says, until the next layout pass.
            fun previewPhotoAt(index: Int) =
                if (index !in 0 until items.itemCount) {
                    null
                } else {
                    (items.peek(index) as? TimelineItem.Photo)?.photo?.takeIf { it.preview != null }
                }
            playingIds =
                selectPlayingIndices(
                    visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index },
                    hasPreview = { previewPhotoAt(it) != null },
                    scrolling = false,
                ).mapNotNull { previewPhotoAt(it)?.photoId }.toSet()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        modifier = modifier,
    ) {
        items(
            count = items.itemCount,
            // A Header's date alone isn't a unique key: the same calendar date can
            // recur non-adjacently in the list (UTC sort order vs. local-day
            // grouping aren't monotonic once tzOffsetMin varies between photos) --
            // see TimelineItem.Header's own doc for why anchorPhotoId exists.
            key = items.itemKey { item -> if (item is TimelineItem.Header) "header-${item.anchorPhotoId}" else (item as TimelineItem.Photo).photo.photoId },
            contentType = items.itemContentType { item -> if (item is TimelineItem.Header) "header" else "photo" },
            span = { index -> if (items.peek(index) is TimelineItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { index ->
            when (val item = items[index]) {
                is TimelineItem.Header -> DateHeader(item)
                is TimelineItem.Photo ->
                    PhotoCell(
                        item.photo,
                        host,
                        playPreview = item.photo.photoId in playingIds,
                        onClick = { onPhotoClick(item.photo) },
                    )
                null -> PlaceholderCell()
            }
        }
    }
}

@Composable
private fun DateHeader(header: TimelineItem.Header) {
    Text(
        text = header.date.format(HEADER_FORMATTER),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Prefers [GRID_THUMB_SIZE]; falls back to the smallest available rung for an asset
 * that's missing it — e.g. a plain-attach rendition, which (per plan step 1.9's own
 * known gap) never gets thumbnails uploaded at all, leaving [PhotoEntity.thumbs] empty. */
@Composable
private fun PhotoCell(
    photo: PhotoEntity,
    host: String?,
    playPreview: Boolean,
    onClick: () -> Unit,
) {
    val chosen =
        photo.thumbs[GRID_THUMB_SIZE]?.let { GRID_THUMB_SIZE to it }
            ?: photo.thumbs.entries.minByOrNull { it.key }?.let { it.key to it.value }
    if (host == null || chosen == null) {
        // Still tappable: a photo with no thumbnails (e.g. a video uploaded before video
        // thumbnails existed) must remain reachable, since the detail screen is where
        // "Repair thumbnails" lives. Only the paging placeholder for a not-yet-loaded
        // item stays inert.
        PlaceholderCell(onClick = onClick)
        return
    }
    val (longestEdge, entry) = chosen
    // The tap handler is on the container, not the image, so it still fires when a
    // preview is playing over the still.
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
        AsyncImage(
            model =
                EncryptedThumbRef(
                    photoId = photo.photoId,
                    longestEdge = longestEdge,
                    url = "https://$host/thumbs/${entry.key}",
                    iv = entry.iv,
                    encDek = photo.encDek,
                ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (playPreview) {
            photo.previewRef(host)?.let { ref -> VideoPreview(ref, Modifier.matchParentSize(), cover = true) }
        }
    }
}

@Composable
private fun PlaceholderCell(onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

private val HEADER_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
