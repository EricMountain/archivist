package fr.enry.archivist.ui.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The timeline's scrollbar, and the fast-scroll rail it turns into on a long press.
 *
 * Replaces the platform's own `LazyVerticalGrid` scroll indicator, whose position comes
 * from `LazyGridLayoutInfo.calculateContentSize()` — an estimate extrapolated from the
 * average height of whichever rows happen to be on screen, which a grid mixing
 * full-width date headers with square photo cells never gives a stable answer to.
 *
 * Idle: a thin thumb whose position is the first visible photo's own local day, per
 * [RailScale] — density-weighted once a histogram is cached, linear in elapsed time
 * otherwise, so it means the same thing whether the library is dense or sparse there.
 *
 * Peeking: scrolling the grid by hand — an ordinary swipe or fling, not touching the
 * rail at all — surfaces the full labelled rail ([RailScale.ticks]), an enlarged thumb,
 * a date label, and a [PositionCursor] marking the current position on the rail, for as
 * long as the scroll is moving plus a short linger afterwards ([PEEK_LINGER_MS]). This
 * used to be the one thing a long press was needed for, which was actively misleading:
 * the very first frame of a long press named whatever day the touch's raw Y happened to
 * land on along the narrow hit-strip — unrelated to wherever the user was actually
 * scrolled to in the grid, since the strip runs the full height of the screen
 * regardless of scroll position. The idle thumb already computed the *correct* day for
 * its own position; peeking just means showing it, and everything else the rail can
 * show about it, without requiring a touch at all.
 *
 * The rail is magnified while just peeking too, centred on the current idle position —
 * not a flat, unwarped layout that only turns into a lens once a finger lands. A touch
 * can start anywhere on the strip, though, often well away from that idle position — so
 * a press selects the tick already drawn beside the finger, and the rail's drawing then
 * eases (`railLens`) into the held layout from wherever it was already showing, so
 * nothing about the rail's rendering jumps.
 *
 * Grabbing the rail always takes a long press, peeking or not: a swipe on the strip must
 * scroll the grid like anywhere else. A swipe that starts on the strip suppresses the peek
 * for its own duration (`stripSwipe`), so the rail doesn't appear under the finger doing it.
 *
 * Held: a long press turns the rail
 * into this same labelled synthesis with the thumb tracking the finger **absolutely** —
 * the y it is touched at is *where it is drawn*, always. The first version accumulated
 * per-frame deltas through a velocity-dependent gain instead, which meant a full-height
 * drag moved the selection a fraction of the range and the thumb visibly lagged the
 * finger; direct mapping is what "follow my finger" actually requires.
 *
 * What the touched position *selects*, though, is not simply the day at the finger's
 * unmagnified position: the selection moves with the finger's *movement*
 * ([advanceSelection]), finely when the finger moves slowly and directly when it moves
 * fast ([dragGain]), and never moves on its own while the finger is still. The rail is
 * drawn through a [Lens] centred on the selection and placed at the finger, so the tick
 * beside the finger is always the day the pill names — a slow drag slides the finger
 * along a magnified, stationary ruler, and a fast one pulls the ruler along with it. An
 * earlier version read the selection off a lens anchor that chased the finger over time
 * (`chaseAnchor`), so the selection, and the grid with it, kept moving for a few hundred
 * milliseconds after the finger stopped; only the *drawing* of the rail eases now
 * (`railLens`), never what's selected.
 *
 * Release: a touchscreen's own reported position is not trustworthy in the last few
 * samples before liftoff — as a fingertip peels off the glass its contact patch shrinks
 * asymmetrically, which on real hardware measurably drags the reported centroid, and at
 * this magnifier's own near-anchor zoom a drag of even a couple of raw pixels can select
 * a meaningfully different day. Committing straight off the final `onDrag` sample (what
 * the first version of this did) meant that sensor artifact, not the finger's actual
 * last deliberate position, decided where the grid jumped to on release — read by a user
 * as "the timeline jumps off to a random place the instant I lift my finger." Every
 * `onDrag`/`onStart` sample is timestamped and kept in `releaseHistory`; [onEnd] commits
 * whatever [settledSample] resolves to — the most recent sample old enough to have sat
 * there for [RELEASE_SETTLE_MS] without being immediately followed by release — rather
 * than the raw last sample. Nothing about what's *drawn* changes: `heldRaw` is still
 * always-absolute, and the thumb/cursor/pill track the finger with zero added latency
 * the whole time this is filtering — only which day a release actually commits to is
 * affected, and only in the case a naive implementation gets wrong.
 */
@Composable
fun TimelineScrollbar(
    gridState: LazyGridState,
    items: LazyPagingItems<PhotoEntity>,
    bounds: TimelineBounds?,
    histogram: TimelineHistogram?,
    onScrub: suspend (LocalDate?) -> Unit,
    onCommit: (RailPosition) -> Unit,
    modifier: Modifier = Modifier,
    // Where the rail's track begins, measured from the top of this composable: keeps the
    // floating menu button off the rail's "present" end, and touches beside the button
    // (above this) out of the gesture.
    trackTopInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    // Density-weighted once the histogram is cached, linear in time until then — see
    // RailScale. Null only on a first launch that hasn't reached the server at all.
    val scale =
        remember(histogram, bounds) { railScale(histogram, bounds) } ?: run {
            content()
            return
        }

    val haptics = LocalHapticFeedback.current
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    // heldRaw is where the finger physically is — always what the thumb, cursor and pill
    // are drawn at. heldSelected is the underlying position selected, moved only by the
    // finger moving (advanceSelection) — see this composable's own doc.
    //
    // Both are State, not plain vars: they're read from closures inside
    // pointerInput/LaunchedEffect coroutines that don't restart on every recomposition.
    var heldRaw by remember { mutableStateOf<Float?>(null) }
    var heldSelected by remember { mutableStateOf<Float?>(null) }
    var lastDragNanos by remember { mutableLongStateOf(0L) }
    // Finger speed in dp/ms, smoothed over DRAG_SPEED_SMOOTHING_MS so a single noisy
    // sample doesn't flip the drag between fine and coarse.
    var dragSpeed by remember { mutableFloatStateOf(0f) }

    // Timestamped (nanoTime, selectedFraction) samples for the current drag, oldest
    // first — what [onEnd] uses via [settledSample] to commit to, instead of the raw
    // final sample, to filter out a touchscreen's own liftoff jitter. Plain `remember`
    // (not State-backed) is enough: it's a stable reference mutated in place, read only
    // from within the same `pointerInput` coroutine that writes it, never from
    // `derivedStateOf` elsewhere — same reasoning as any other mutable collection here.
    val releaseHistory = remember { ArrayDeque<Pair<Long, Float>>() }

    // Both derived from one photo lookup, not two separate ones, so the label and the
    // thumb's position can never disagree about which photo they're describing.
    //
    // Read through the grid's own layout (the item actually at the top of the screen),
    // and only when that layout agrees with [items] — see [syncedVisiblePhotos]. When
    // they disagree the last answer stands: the photo on screen hasn't moved, only the
    // list's indices have.
    val idlePhoto by remember(items, scale) {
        var last: PhotoEntity? = null
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
            first?.let { syncedVisiblePhotos(listOf(it), items)?.single() }?.also { last = it } ?: last
        }
    }
    val idleDay = idlePhoto?.localDate()

    // Also must be a State, not a plain val, for the same reason as heldSelected: the
    // tap handler reads it from inside a pointerInput coroutine that doesn't restart
    // every recomposition.
    //
    // Finer than the day: how far the top of the grid is through that day's photos
    // (fractionalTopIndex/progressAt), so the thumb glides as the grid scrolls rather
    // than stepping once per day — and so that, after a release, it rests exactly where
    // the finger left it instead of hopping back to the top of the day. Falls back to the
    // day's own start while the grid's layout and [items] disagree (see idlePhoto).
    val idleFraction by remember(items, scale) {
        derivedStateOf {
            val photo = idlePhoto ?: return@derivedStateOf null
            val day = photo.localDate()
            val top = gridState.fractionalTopIndex()
            val range = dayRange(items.itemCount, day) { i -> items.peek(i)?.localDate() }
            // Through syncedVisiblePhotos, never a bare peek at the layout's index: a rail
            // scrub rebuilds the pager, and for a frame the layout still reports indices
            // past the end of the new, shorter list (crashed live: index 204, size 121).
            val synced = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { syncedVisiblePhotos(listOf(it), items)?.single() }
            if (top == null || range == null || synced?.photoId != photo.photoId) {
                scale.fractionOfDay(day)
            } else {
                scale.fractionOf(day, progressAt(top, range.first, range.last - range.first + 1, scale.dayCount(day)))
            }
        }
    }

    // Where the finger last was, kept on screen for [RELEASE_LINGER_MS] after lift-off (or a
    // tap). Without it the thumb/label/rail snap straight back to the idle position, which
    // still describes the *old* scroll position until the commit's fetch and scroll land —
    // so a tap on the rail showed the line where it was tapped for one frame and then
    // yanked it back to the current date.
    var lingering by remember { mutableStateOf<LingeringSelection?>(null) }
    LaunchedEffect(lingering) {
        if (lingering != null) {
            delay(RELEASE_LINGER_MS)
            lingering = null
        }
    }

    val thumbFraction = heldRaw ?: lingering?.fraction ?: idleFraction ?: 0f

    // Peeking: visible while a long press is held (unchanged), or while the grid itself
    // is scrolling by hand, for [PEEK_LINGER_MS] after it stops. `LazyGridState`'s own
    // `isScrollInProgress` covers both a drag and the fling it releases into — a fling
    // is still "scrolling by hand" as far as this is concerned, it just has no finger on
    // it anymore. Keyed directly on that boolean rather than collected as a flow: a
    // `LaunchedEffect` restarting on every key change is exactly "cancel the pending
    // hide and show immediately" when scrolling resumes mid-linger, with no explicit
    // cancellation logic to get wrong.
    var scrollPeekVisible by remember { mutableStateOf(false) }
    // True from a swipe on the strip being recognised until the scroll it started has
    // stopped. Swiping *on* the strip scrolls the grid like anywhere else, and the rail
    // must not pop up under the very finger doing it — it would suggest the strip is a
    // control (it is, but only after a long press) and cover what's being scrolled.
    var stripSwipe by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (!gridState.isScrollInProgress) stripSwipe = false
        if (gridState.isScrollInProgress) {
            scrollPeekVisible = true
        } else {
            delay(PEEK_LINGER_MS)
            scrollPeekVisible = false
        }
    }
    // Gated on idleFraction being non-null too: an enlarged thumb pinned at the top with
    // a label reading nothing would-be-misleading before the first photo has loaded.
    val peeking = heldRaw != null || lingering != null || (scrollPeekVisible && !stripSwipe && idleFraction != null)

    // The lens the rail should be drawn through: in place around idleFraction while just
    // peeking (so the rail is already magnified around wherever the grid is before any
    // finger touches it), centred on the selection and placed at the finger while held
    // (so the selected day is the tick beside the finger), and frozen where the finger left
    // it while the release lingers.
    val peekingNow by rememberUpdatedState(peeking)
    val heldNow = heldRaw
    val heldSelectedNow = heldSelected
    val targetLens =
        when {
            heldNow != null && heldSelectedNow != null -> Lens(heldSelectedNow, heldNow)
            lingering != null -> lingering!!.lens
            else -> idleFraction?.let { Lens(it, it) }
        }
    val targetLensNow by rememberUpdatedState(targetLens)
    // What's actually drawn eases toward targetLens (a critically-damped spring, a few tens
    // of ms) rather than jumping to it — a press, a release and the linger ending each
    // change the lens discontinuously, and the rail visibly lurched at each. Purely
    // cosmetic: selection never reads this.
    val railCenter = remember { Animatable(0f) }
    val railAt = remember { Animatable(0f) }
    var railLens by remember { mutableStateOf<Lens?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { targetLensNow to peekingNow }.collectLatest { (target, visible) ->
            if (target == null) return@collectLatest
            if (!visible || railLens == null) {
                // Appearing: start exactly on target rather than sweeping in from wherever
                // the rail was last shown.
                railCenter.snapTo(target.center)
                railAt.snapTo(target.at)
                railLens = target
                return@collectLatest
            }
            coroutineScope {
                launch { railCenter.animateTo(target.center, RAIL_LENS_SPRING) }
                launch { railAt.animateTo(target.at, RAIL_LENS_SPRING) }
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { Lens(railCenter.value, railAt.value) }.collect { if (railLens != null) railLens = it }
    }

    // Scrolling the grid along with the finger, as fast as the network allows and no
    // faster. The whole library is on the rail but only the visited window is in Room, so
    // a fetch per frame is not available; `conflate` is what makes that a pacing problem
    // rather than a queueing one — while a window is loading, every day the finger crosses
    // is dropped except the most recent, so the next fetch always asks for where the
    // finger is *now* rather than working through a backlog of where it has been.
    //
    // Deliberately not a debounce on the hovered day, which is what shipped first: a day
    // is about six pixels of track, so a resting finger wobbles across day boundaries and
    // restarted the timer every time. It only ever fired if the finger was held genuinely
    // still, which read as the feature not being implemented at all.
    //
    // Reads heldSelected, not heldRaw: once the lens is engaged, it's the *selected*
    // underlying day that a scrub should be fetching, not whatever day the finger's raw
    // screen position would name unmagnified.
    LaunchedEffect(scale) {
        snapshotFlow { heldSelected?.let { Scrub(scale.dayAt(it)) } }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .collect { onScrub(it.day) }
    }

    // The scrub above only *fetches* — it's paced by the network and deals in whole days.
    // Where the grid sits is decided here, every frame the finger moves, from whatever is
    // loaded right now: the same fraction of the way through the day's photos as the
    // finger is through the day's track (see GridPosition.kt), so the grid slides with
    // the finger instead of jumping from one day's first photo to the next. Reads the
    // list's contents (through peek) as well as the finger, so it also re-places the grid
    // the moment a scrub's window lands, or paging shifts indices under it.
    //
    // The present (day null) is left to the scrub's own landing: index 0 of whatever
    // window is loaded before that fetch lands is not the present.
    LaunchedEffect(scale, items) {
        snapshotFlow {
            heldSelected?.let(scale::positionAt)?.takeIf { it.day != null }?.let { targetIndexFor(items, it) }
        }.filterNotNull()
            .distinctUntilChanged()
            .collectLatest { gridState.scrollToFractionalIndex(it) }
    }

    // The rail's own width, so its background and labels aren't measured against the
    // narrow touch strip (which clipped the background and wrapped the date pill onto
    // four lines). Only the strip inside it takes pointer input — the rest of this is
    // transparent and non-interactive, so photos underneath stay tappable.
    //
    // The gesture is detected on this outer Box, wrapping the grid, not on a strip laid
    // over it: siblings don't share pointer events, so a strip on top swallowed every
    // touch in that area and the grid could never be scrolled from there. A parent sees
    // events (Initial pass) before its children and consumes them only once a long press
    // has confirmed, so everything else reaches the grid untouched.
    Box(
        modifier.pointerInput(scale, trackTopInset) {
            val stripStartPx = { size.width - HIT_TARGET_WIDTH.toPx() }
            // Gestures arrive in this outer Box's coordinates; the track starts lower.
            val trackTopPx = trackTopInset.toPx()
            detectFastScrollGesture(
                inStrip = { it.x >= stripStartPx() && it.y >= trackTopPx },
                tapEnabled = { peekingNow },
                onTap = { y ->
                    // What's drawn at the tap is warped through the lens the rail is
                    // showing, so select the underlying position that tick stands for.
                    val f = fractionAt(y - trackTopPx, trackHeightPx)
                    val lens = railLens ?: Lens(f, f)
                    val selected = lens.unwarp(f)
                    val position = scale.positionAt(selected)
                    lingering = LingeringSelection(f, Lens(selected, f), position.day)
                    onCommit(position)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onSwipe = { stripSwipe = true },
                onRelease = { if (!gridState.isScrollInProgress) stripSwipe = false },
                onStart = { y ->
                    val f = fractionAt(y - trackTopPx, trackHeightPx)
                    // Starts on whatever the rail is showing beside the finger right now —
                    // the tick under it is the day selected, so nothing jumps on press.
                    val selected = (railLens ?: Lens(f, f)).unwarp(f)
                    heldRaw = f
                    heldSelected = selected
                    lingering = null
                    stripSwipe = false
                    dragSpeed = 0f
                    lastDragNanos = System.nanoTime()
                    releaseHistory.clear()
                    releaseHistory.addLast(lastDragNanos to selected)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { y ->
                    val f = fractionAt(y - trackTopPx, trackHeightPx)
                    val from = heldRaw ?: f
                    val now = System.nanoTime()
                    val elapsedMs = ((now - lastDragNanos) / 1_000_000f).coerceAtLeast(1f)
                    lastDragNanos = now
                    val instant = abs(f - from) * trackHeightPx / density / elapsedMs
                    dragSpeed += (instant - dragSpeed) * (1f - exp(-elapsedMs / DRAG_SPEED_SMOOTHING_MS))
                    val selected = advanceSelection(heldSelected ?: f, from, f, dragGain(dragSpeed))
                    heldRaw = f
                    heldSelected = selected
                    releaseHistory.addLast(now to selected)
                    while (releaseHistory.size > 1 && now - releaseHistory.first().first > RELEASE_HISTORY_WINDOW_NS) {
                        releaseHistory.removeFirst()
                    }
                },
                onEnd = {
                    // Always commits, even when the finger settled here long
                    // enough that the window is already loaded: the commit is
                    // also what rebuilds the pager, and that is what clears
                    // PREPEND's latched end-of-pagination so the timeline can be
                    // scrolled back toward the present. The repository skips the
                    // refetch when the day is unchanged, so that costs nothing.
                    //
                    // Uses settledSample rather than heldSelected directly — see
                    // this composable's own doc, "Release" paragraph, for why the
                    // raw final sample can't be trusted on its own.
                    val settled = settledSample(releaseHistory, System.nanoTime(), RELEASE_SETTLE_MS) ?: heldSelected
                    settled?.let { onCommit(scale.positionAt(it)) }
                    val at = heldRaw
                    if (at != null && settled != null) {
                        lingering = LingeringSelection(at, Lens(settled, at), scale.dayAt(settled))
                    }
                    heldRaw = null
                    heldSelected = null
                    lastDragNanos = 0L
                    releaseHistory.clear()
                },
            )
        },
    ) {
        content()

        Box(Modifier.align(Alignment.TopEnd).padding(top = trackTopInset).fillMaxHeight().width(RAIL_WIDTH)) {
        if (peeking) {
            TimelineRail(scale = scale, trackHeightPx = trackHeightPx, lens = railLens)
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(HIT_TARGET_WIDTH)
                .onSizeChanged { trackHeightPx = it.height.toFloat() },
        )

//        if (peeking) {
//            PositionCursor(
//                fraction = thumbFraction,
//                trackHeightPx = trackHeightPx,
//                modifier = Modifier.align(Alignment.TopEnd),
//            )
//        }

        ScrollbarThumb(
            fraction = thumbFraction,
            trackHeightPx = trackHeightPx,
            expanded = peeking,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // Two branches rather than one merged "day", because the two null cases mean
        // different things: held at the very top of the rail means "back to the
        // present" (scale.dayAt returns null on purpose, see its own doc), which
        // SelectedDateLabel renders as "Latest" — a real answer. Peeking from an
        // ordinary scroll with no day yet just means nothing has loaded to report,
        // which `peeking`'s own idleFraction != null guard already excludes.
        //
        // The label is drawn at heldRaw (the finger's own position — it sits beside the
        // touch point, see SelectedDateLabel's own doc) but names heldSelected's day —
        // the two agree exactly where the lens is un-engaged, and diverge exactly where
        // the whole feature is supposed to.
        val heldRawSnapshot = heldRaw
        when {
            heldRawSnapshot != null ->
                SelectedDateLabel(
                    day = scale.dayAt(heldSelected!!),
                    fraction = heldRawSnapshot,
                    trackHeightPx = trackHeightPx,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            lingering != null ->
                SelectedDateLabel(
                    day = lingering!!.day,
                    fraction = lingering!!.fraction,
                    trackHeightPx = trackHeightPx,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            peeking ->
                SelectedDateLabel(
                    day = idleDay,
                    fraction = idleFraction ?: 0f,
                    trackHeightPx = trackHeightPx,
                    modifier = Modifier.align(Alignment.TopStart),
                )
        }
        }
    }
}

/** One destination the rail can select. A wrapper rather than a bare `LocalDate?`
 * because `null` means "back to the present" — a real destination, distinct from "not
 * dragging". */
private data class Scrub(val day: LocalDate?)

/** Where a released touch was, and what it selected — see `lingering` in [TimelineScrollbar]. */
private data class LingeringSelection(val fraction: Float, val lens: Lens, val day: LocalDate?)

/** How far back finger speed is averaged for [dragGain] — long enough to smooth out one
 * noisy touch sample, short enough that speeding up or slowing down takes effect at once. */
private const val DRAG_SPEED_SMOOTHING_MS = 40f

/** Critically damped and quick: the rail's drawing settles within a few frames of the
 * lens changing, without overshoot. */
private val RAIL_LENS_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

private val HIT_TARGET_WIDTH = 148.dp
// Wide enough that a tick label — drawn growing left from just past the touch strip,
// see TimelineRail — fits entirely within the rail's own background rather than
// spilling out past its left edge onto plain, untinted screen: HIT_TARGET_WIDTH (48dp)
// + the larger of the two tick gaps (FINE_TICK_GAP, 20dp) + a generous label-width
// allowance (a labelSmall "24 Aug 2026"-ish string, plus headroom for a larger system
// font scale) comfortably clears 96dp, which is what this used to be before tick labels
// were moved to grow outward from the strip instead of being clipped/wrapped against it.
private val RAIL_WIDTH = 168.dp
// Gap between the touch strip's own left edge and where tick labels are drawn (they grow
// further left from there, unbounded — see TimelineRail). Fine ticks get noticeably more
// clearance than coarse: they're the ruler a finger is actually trying to read while
// dragging, so they're the ones a real fingertip (wider than the nominal touch strip)
// would otherwise cover.
private val COARSE_TICK_GAP = 4.dp
private val FINE_TICK_GAP = 20.dp

private val IDLE_THUMB = 4.dp to 40.dp
private val HELD_THUMB = RAIL_WIDTH - (COARSE_TICK_GAP * 2) to 2.dp

/** How long the peek stays visible after the grid stops scrolling. Long enough to
 * actually read a date, short enough that it reads as "while scrolling" rather than a
 * fixture that's just always there. */
private const val PEEK_LINGER_MS = 1200L

/** How long the rail stays put where the finger left it after release or a tap. Longer
 * than [PEEK_LINGER_MS]: the committed jump has to fetch and scroll before the idle
 * position catches up, and the user wants time to see where they landed. */
private const val RELEASE_LINGER_MS = 2000L

/**
 * The whole library laid out along the track, so the drag has something to aim at.
 *
 * [lens], when non-null, warps where every tick is *drawn* — [Lens]'s own doc — and
 * adds a run of day-level [RailScale.fineTicks] around its centre: [scale]'s ordinary
 * [RailScale.ticks] are a sparse, whole-library set of ~14 month/year labels, nowhere
 * near fine enough to show what the magnifier has just made room for. The coarse ticks
 * keep drawing everywhere else (also warped, so the whole rail stays one continuous,
 * consistent picture rather than a magnified island stitched onto an unmagnified one).
 *
 * The caller passes a lens whenever the rail is shown at all, not only while a finger is
 * down — see `TimelineScrollbar`'s own `railLens` — so this is magnified around the
 * current idle position even during a plain scroll-triggered peek, not a flat layout that
 * only becomes a lens once held.
 *
 * Tick labels are drawn growing left from just past the touch strip's own edge
 * ([wrapContentWidth]-unbounded, not a fixed-width box), rather than measured against
 * [RAIL_WIDTH] and left to run rightward under the strip: text sized to fit its own box
 * either clips or wraps, and the strip is exactly where a real finger sits, so anything
 * drawn under or near it is unreadable while it's actually in use.
 */
@Composable
private fun TimelineRail(
    scale: RailScale,
    trackHeightPx: Float,
    lens: Lens? = null,
) {
    val density = LocalDensity.current
    val ticks = remember(scale) { scale.ticks() }
    val fineTicks = remember(scale, lens?.center) { lens?.let { scale.fineTicks(it.center) } ?: emptyList() }

    // Coarse ticks are spaced evenly along the *unwarped* track, which is exactly what
    // the lens's compressed far side ruins: several months' worth of ticks can warp into
    // a handful of pixels and overprint each other. Only warping needs decluttering —
    // the unwarped layout already has each tick its own room — so this is computed once
    // per lens rather than folded into the loop below.
    val visibleTicks =
        remember(ticks, lens, trackHeightPx) {
            if (lens == null) {
                ticks.map { it to it.fraction }
            } else {
                val minGapPx = with(density) { 16.dp.toPx() } / trackHeightPx.coerceAtLeast(1f)
                declutterTicks(ticks, minGapPx) { lens.warp(it) }
            }
        }

    // No backing panel: each label is its own translucent white bubble over the photos.
    Box(Modifier.fillMaxSize()) {
        for ((tick, drawnAt) in visibleTicks) {
            val y = with(density) { (drawnAt * trackHeightPx).toDp() }
            TickBubble(
                text = tick.label,
                fontWeight = if (tick.major) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.Black.copy(alpha = if (tick.major) 0.9f else 0.6f),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = COARSE_TICK_GAP, y = y - TICK_BUBBLE_HALF_HEIGHT),
            )
        }
        // Drawn after (so visually on top of) the coarse ticks, and given *more*
        // clearance from the touch strip, not less: these are the labels a finger is
        // actually trying to read while dragging, so they're the ones that most need to
        // sit clear of it rather than tucked in close where a real fingertip covers them.
        for (tick in fineTicks) {
            val y = with(density) { (lens!!.warp(tick.fraction) * trackHeightPx).toDp() }
            TickBubble(
                text = tick.label,
                fontWeight = if (tick.major) FontWeight.Bold else FontWeight.Normal,
                color = if (tick.major) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.8f),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = FINE_TICK_GAP, y = y - TICK_BUBBLE_HALF_HEIGHT),
            )
        }
    }
}

/** A rail label: dark text on an 80%-opaque white pill, legible over any photo. */
@Composable
private fun TickBubble(
    text: String,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        modifier =
            modifier
                .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Half the height of a [TickBubble] (labelSmall's 16dp line plus 2dp padding each side),
 * so its centre lands on the tick's y. */
private val TICK_BUBBLE_HALF_HEIGHT = 10.dp

/**
 * Thins [ticks] so none land closer together than [minGapFraction] once [warp] is
 * applied — the lens's compressed far side can otherwise warp a dozen evenly-spaced
 * coarse ticks into a handful of overlapping pixels. Greedy in warped order: walk ticks
 * sorted by their drawn position, keep the first, and keep each later one only once
 * it's cleared the gap from whichever tick is still kept. A major tick (a year
 * boundary) is allowed to bump a minor one it lands on top of, so year labels don't
 * vanish into a run of months.
 */
private fun declutterTicks(
    ticks: List<TimelineTick>,
    minGapFraction: Float,
    warp: (Float) -> Float,
): List<Pair<TimelineTick, Float>> {
    val positioned = ticks.map { it to warp(it.fraction) }.sortedBy { it.second }
    val kept = mutableListOf<Pair<TimelineTick, Float>>()
    for (entry in positioned) {
        val last = kept.lastOrNull()
        when {
            last == null || entry.second - last.second >= minGapFraction -> kept.add(entry)
            entry.first.major && !last.first.major -> kept[kept.lastIndex] = entry
        }
    }
    return kept
}

/** The precise date at the touch point, placed clear to the *left* of the rail: sitting
 * beside the finger it was simply covered by it and unreadable.
 *
 * A custom [Layout] rather than [wrapContentSize] + [onSizeChanged] + remembered state:
 * the latter only learns the pill's width *after* a layout pass has already happened, so
 * the offset it computes always applies one frame late — the first frame (and every
 * frame the text changes width, e.g. "9 Jan 2026" to "24 Aug 2026") briefly draws at the
 * old offset, which is exactly the initial flash to the wrong side this replaced. Measuring
 * the pill and placing it in the same pass has no such lag: by the time anything is drawn,
 * [placeable] already knows its own width, so the offset that puts its right edge
 * [LABEL_RAIL_GAP] short of the rail's left edge (x = 0, where this composable is anchored
 * via `Alignment.TopStart`) is correct from frame one.
 */
@Composable
private fun SelectedDateLabel(
    day: LocalDate?,
    fraction: Float,
    trackHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { LABEL_RAIL_GAP.toPx() }
    val centerYPx = fraction * trackHeightPx

    Layout(
        modifier = modifier,
        content = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
            ) {
                Text(
                    // Null is the top of the rail, which means "back to the present"
                    // rather than any particular date — so it says that instead of
                    // naming one.
                    text = day?.let(SELECTED_DATE_FORMATTER::format) ?: "Latest",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        },
    ) { measurables, _ ->
        // Unbounded, or the pill is measured against the rail's own width and the date
        // wraps one character per line. This Layout claims zero size of its own (it
        // isn't part of the rail's flow, just an anchor point for placement), so the
        // incoming constraints are irrelevant to it anyway.
        val placeable = measurables[0].measure(Constraints())
        layout(0, 0) {
            placeable.placeRelative(
                x = -(placeable.width + gapPx).roundToInt(),
                y = (centerYPx - placeable.height / 2f).roundToInt(),
            )
        }
    }
}

/** Gap between the pill's right edge and the rail's left edge — see [SelectedDateLabel]. */
private val LABEL_RAIL_GAP = 4.dp

/**
 * A guide line across the full touch strip at the current position — the thing a finger
 * actually has to find. The thumb alone ([ScrollbarThumb]) is a 10dp bar tucked against
 * the very edge of the screen; this spans the whole [HIT_TARGET_WIDTH] strip instead, so
 * there's a wide, easy target rather than a sliver to land a thumb on precisely. It's
 * also the reason grabbing the rail during a peek can skip the long press at all: the
 * cursor marks exactly the y [detectFastScrollGesture] would treat as "here", so a touch
 * on it starts a drag with nothing to jump to — it's already where the finger is.
 */
@Composable
private fun PositionCursor(
    fraction: Float,
    trackHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val y = with(density) { (fraction * trackHeightPx).toDp() }
    Box(
        modifier
            .offset(y = y - CURSOR_HEIGHT / 2)
            .width(HIT_TARGET_WIDTH)
            .height(CURSOR_HEIGHT)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    )
}

private val CURSOR_HEIGHT = 2.dp

@Composable
private fun ScrollbarThumb(
    fraction: Float,
    trackHeightPx: Float,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val (widthDp, heightDp) = if (expanded) HELD_THUMB else IDLE_THUMB
    val heightPx = with(LocalDensity.current) { heightDp.toPx() }
    val y = (fraction * trackHeightPx - heightPx / 2f).coerceIn(0f, (trackHeightPx - heightPx).coerceAtLeast(0f))
    Box(
        modifier
            .padding(horizontal = COARSE_TICK_GAP)
            .offset { IntOffset(0, y.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
    )
}

private val SELECTED_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy")

private const val LONG_PRESS_MS = 250L

/** How long a sample has to have sat in `releaseHistory` before [settledSample] will
 * commit to it — long enough to clear a touchscreen's own liftoff-jitter window (which
 * on real hardware shows up in the last one to three reported samples, roughly 10–50ms
 * at typical 60–120Hz touch sampling), short enough that no user could perceive it as
 * added latency; this only changes what a release commits to, never anything drawn in
 * real time. See `TimelineScrollbar`'s own doc, "Release" paragraph. */
private const val RELEASE_SETTLE_MS = 50L
private const val RELEASE_SETTLE_NS = RELEASE_SETTLE_MS * 1_000_000L

/** How far back `releaseHistory` is kept before old samples are pruned — several times
 * [RELEASE_SETTLE_MS] so a settled sample is always available, with no significance
 * beyond that (a drag can run for seconds; this just keeps the buffer from growing
 * unbounded rather than tuning any actual behaviour). */
private const val RELEASE_HISTORY_WINDOW_NS = RELEASE_SETTLE_NS * 5

/**
 * The most recent entry in [history] — timestamped in [System.nanoTime]-comparable
 * nanoseconds, oldest first — old enough as of [nowNanos] to have survived
 * [RELEASE_SETTLE_MS] without being immediately followed by release. Falls back to the
 * oldest entry when nothing qualifies (an entire drag shorter than the settle window —
 * essentially a tap — reasonably commits to wherever it started), and to `null` only
 * when [history] is itself empty.
 */
internal fun settledSample(
    history: List<Pair<Long, Float>>,
    nowNanos: Long,
    settleMs: Long = RELEASE_SETTLE_MS,
): Float? {
    val settleNanos = settleMs * 1_000_000L
    return history.lastOrNull { nowNanos - it.first >= settleNanos }?.second
        ?: history.firstOrNull()?.second
}

/**
 * Long-press on the rail, then drag. Always — even while the rail is peeking: a touch on
 * the strip has to stay an ordinary swipe that scrolls the grid, and the only thing
 * telling the two apart is the long press. Nothing is consumed until it confirms, so a
 * swipe that starts on the rail scrolls the grid normally, and a tap ([tapEnabled], i.e. the rail is showing) calls [onTap] and swallows the lift so the cell underneath isn't opened —
 * [LazyGridState]'s own `scrollable` modifier sees the same unconsumed events, and this
 * detector just times out having claimed nothing. [onSwipe]/[onRelease] bracket a touch
 * that did *not* become a drag, so the caller can tell a strip swipe from a peek. Everything after confirmation *is*
 * consumed, which is what stops the grid reacting to the same drag.
 *
 * Positions are handed on raw and absolute; the caller maps y to a fraction of the
 * track. There is deliberately no delta accumulation and no gain here.
 */
private suspend fun PointerInputScope.detectFastScrollGesture(
    inStrip: (Offset) -> Boolean,
    tapEnabled: () -> Boolean,
    onTap: (y: Float) -> Unit,
    onSwipe: () -> Unit,
    onRelease: () -> Unit,
    onStart: (y: Float) -> Unit,
    onDrag: (y: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downPosition = down.position
        if (!inStrip(downPosition)) return@awaitEachGesture

        // Times out (returns null) only if the pointer stayed down, within touch
        // slop, for the full duration — that is the long press. Any early return
        // means the gesture resolved as something else and this must not claim it.
        var swiped = false
        val tapGoes = tapEnabled()
        val abortedEarly =
            withTimeoutOrNull(LONG_PRESS_MS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                    if (!change.pressed) {
                        // A tap. On a visible rail it means "go there", so the lift is
                        // consumed (Initial pass, before the grid) to stop it also opening
                        // the photo underneath — a consumed up cancels the cell's click.
                        if (tapGoes) {
                            change.consume()
                            onTap(change.position.y)
                        }
                        return@withTimeoutOrNull
                    }
                    if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                        swiped = true
                        return@withTimeoutOrNull
                    }
                }
            } != null
        if (abortedEarly) {
            // Let the grid have the swipe; wait out the rest of it so `onRelease` fires
            // when the finger lifts rather than the instant the swipe is recognised.
            if (swiped) onSwipe()
            while (currentEvent.changes.any { it.pressed }) awaitPointerEvent(PointerEventPass.Final)
            onRelease()
            return@awaitEachGesture
        }

        onStart(downPosition.y)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            onDrag(change.position.y)
            change.consume()
        }
        onEnd()
    }
}

internal fun fractionAt(
    y: Float,
    trackHeightPx: Float,
): Float = if (trackHeightPx <= 0f) 0f else (y / trackHeightPx).coerceIn(0f, 1f)
