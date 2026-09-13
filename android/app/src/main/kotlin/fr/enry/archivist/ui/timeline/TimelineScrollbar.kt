package fr.enry.archivist.ui.timeline

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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
 * grabbing the rail doesn't snap its rendering straight to the touch point (see
 * `railAnchor`'s own doc for why that's a separate, purely cosmetic anchor from the one
 * driving selection): it eases toward the touch from wherever it was already showing,
 * the same continuous chase used everywhere else in this file, so nothing about the
 * rail's own rendering ever jumps.
 *
 * Peeking is also what lets grabbing the rail skip the long press entirely — see
 * [detectFastScrollGesture]'s `skipConfirmation`. The cursor drawn across the touch
 * strip during a peek is the point of the whole thing: it gives a finger something
 * specific to aim at, so putting it down there and starting to drag continues smoothly
 * from wherever the grid already was, rather than the long-press gesture's old
 * behaviour of snapping straight to whatever arbitrary height the finger first touched.
 *
 * Held: a long press (or, while peeking, any touch on the strip at all) turns the rail
 * into this same labelled synthesis with the thumb tracking the finger **absolutely** —
 * the y it is touched at is *where it is drawn*, always. The first version accumulated
 * per-frame deltas through a velocity-dependent gain instead, which meant a full-height
 * drag moved the selection a fraction of the range and the thumb visibly lagged the
 * finger; direct mapping is what "follow my finger" actually requires.
 *
 * What the touched position *selects*, though, is no longer the same thing it is drawn
 * at, once [lensAnchor] is engaged: the magnifier (`lensWarp`/`lensUnwarp`'s own doc)
 * warps the *rail* — where each tick is drawn, and what underlying position a given
 * touch position resolves to — around [lensAnchor], which continuously *chases* the
 * finger ([chaseAnchor]) rather than sitting fixed where the drag began: a finger moving
 * slowly gives it time to keep up, so the touch point keeps operating in the warp's
 * steep near-anchor region wherever it currently is (continuous fine adjustment, not
 * just near the drag's starting point); a fast flick outruns it, leaving the touch point
 * out in the shallow far side where the same movement covers a lot of ground (fast
 * travel stays fast). The thumb, cursor and pill are still drawn exactly where the
 * finger physically is; only *which day that is* changes — see [lensAnchor]'s own doc
 * for why a lens fixed for the whole drag instead reads as static and unresponsive to
 * slow, deliberate movement, which is exactly what a magnifier exists to serve. The
 * rail's own tick layout reflows continuously too, but via `railAnchor`, a separate
 * cosmetic anchor that trails [lensAnchor] rather than being driven by it directly — see
 * `railAnchor`'s own doc.
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
    items: LazyPagingItems<TimelineItem>,
    bounds: TimelineBounds?,
    histogram: TimelineHistogram?,
    onScrub: suspend (LocalDate?) -> Unit,
    onCommit: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Density-weighted once the histogram is cached, linear in time until then — see
    // RailScale. Null only on a first launch that hasn't reached the server at all.
    val scale = remember(histogram, bounds) { railScale(histogram, bounds) } ?: return

    val haptics = LocalHapticFeedback.current
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    // heldRaw is where the finger physically is — always what the thumb, cursor and
    // pill are drawn at. lensAnchor starts there too (onStart) but then *chases* it
    // ([chaseAnchor]) rather than sitting fixed for the whole drag: snapping the anchor
    // to match heldRaw exactly, every single frame, would make "exactly where the
    // finger already is" a fixed point on every frame — and a fixed point's local slope
    // is always 1, so the one thing that would actually help disappears entirely. A
    // *fixed-for-the-whole-drag* anchor avoids that trap but trades it for a different
    // one, found live: the rail's tick layout (which is what [lensAnchor] actually
    // drives — see `TimelineRail`) then never reflows again for the rest of the drag,
    // which for a slow, deliberate drag away from wherever the finger first landed reads
    // as the whole rail having frozen solid rather than as a magnifier tracking the
    // finger. Chasing splits the difference without an explicit velocity threshold: it
    // never fully catches up to a moving finger (so there's always *some* gap left to
    // give the near-anchor region its de-amplifying effect), but a finger moving slowly
    // gives it enough time to stay close, keeping fine control live at wherever the
    // finger currently is rather than only right where the drag began.
    var heldRaw by remember { mutableStateOf<Float?>(null) }
    var lensAnchor by remember { mutableStateOf<Float?>(null) }
    // A second, purely cosmetic anchor for what TimelineRail actually draws around — see
    // visualAnchor below for why this can't just be lensAnchor itself: selection needs
    // lensAnchor seeded exactly at the touch point every time (anything else measurably
    // mis-selects relative to the finger from frame one), but that seed is often nowhere
    // near the idle position the rail was just showing, and rendering that same jump
    // would reintroduce the "rail lurches the instant a finger lands" complaint. railAnchor
    // is seeded from the idle position instead and chases lensAnchor exactly like lensAnchor
    // chases heldRaw, so the rail's own layout always eases toward the real anchor rather
    // than snapping to it — cosmetic lag layered on top of the correctness-critical value,
    // never the other way around.
    var railAnchor by remember { mutableStateOf<Float?>(null) }
    var lastDragNanos by remember { mutableLongStateOf(0L) }

    // Timestamped (nanoTime, selectedFraction) samples for the current drag, oldest
    // first — what [onEnd] uses via [settledSample] to commit to, instead of the raw
    // final sample, to filter out a touchscreen's own liftoff jitter. Plain `remember`
    // (not State-backed) is enough: it's a stable reference mutated in place, read only
    // from within the same `pointerInput` coroutine that writes it, never from
    // `derivedStateOf` elsewhere — same reasoning as any other mutable collection here.
    val releaseHistory = remember { ArrayDeque<Pair<Long, Float>>() }

    // Both derived from one photo lookup, not two separate ones, so the label and the
    // thumb's position can never disagree about which photo they're describing.
    val idlePhoto by remember(items, scale) {
        derivedStateOf { nearestPhoto(items, gridState.firstVisibleItemIndex) }
    }
    val idleDay = idlePhoto?.localDate()

    // Also must be a State, not a plain val, for the same reason as heldSelected below:
    // onStart reads it to seed the lens anchor, from inside a pointerInput coroutine that
    // doesn't restart every recomposition.
    val idleFraction by remember(scale) {
        derivedStateOf { idlePhoto?.let { scale.fractionOfDay(it.localDate()) } }
    }

    val thumbFraction = heldRaw ?: idleFraction ?: 0f
    // The *selected* fraction: what heldRaw actually names once the lens is applied.
    // Equal to heldRaw itself with no lens engaged (peeking, or heldRaw null entirely).
    //
    // Must be a State (derivedStateOf), not a plain val: it's read from the onEnd/onScrub
    // closures below, which live inside pointerInput/LaunchedEffect coroutines that don't
    // restart on every recomposition (only when their key — scale — changes). A plain val
    // gets captured by those closures as whatever it happened to equal back when that
    // coroutine last (re)started — typically null, from before any press. Reading a State
    // object instead always reflects the live value, the same way heldRaw/lensAnchor
    // (themselves State-backed) already do.
    val heldSelected by remember {
        derivedStateOf { heldRaw?.let { raw -> lensAnchor?.let { a -> lensUnwarp(raw, a) } ?: raw } }
    }

    // Peeking: visible while a long press is held (unchanged), or while the grid itself
    // is scrolling by hand, for [PEEK_LINGER_MS] after it stops. `LazyGridState`'s own
    // `isScrollInProgress` covers both a drag and the fling it releases into — a fling
    // is still "scrolling by hand" as far as this is concerned, it just has no finger on
    // it anymore. Keyed directly on that boolean rather than collected as a flow: a
    // `LaunchedEffect` restarting on every key change is exactly "cancel the pending
    // hide and show immediately" when scrolling resumes mid-linger, with no explicit
    // cancellation logic to get wrong.
    var scrollPeekVisible by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) {
            scrollPeekVisible = true
        } else {
            delay(PEEK_LINGER_MS)
            scrollPeekVisible = false
        }
    }
    // Gated on idleFraction being non-null too: an enlarged thumb pinned at the top with
    // a label reading nothing would-be-misleading before the first photo has loaded.
    val peeking = heldRaw != null || (scrollPeekVisible && idleFraction != null)

    // What TimelineRail actually warps around: idleFraction while just peeking (so the
    // rail is *already* magnified around wherever the grid currently is, before any
    // finger has touched it — see TimelineRail's own doc for why this matters), railAnchor
    // once held — not lensAnchor directly, which is seeded at the touch point (for
    // selection accuracy) and can be far from idleFraction. railAnchor starts at
    // idleFraction and chases lensAnchor, so this switch is seamless — the rail's own
    // rendering eases toward the new anchor rather than snapping to it — while heldRaw
    // (drawn separately, always absolute) and heldSelected (driven by lensAnchor, not
    // railAnchor) are both correct from the very first frame regardless.
    val visualAnchor = if (heldRaw != null) railAnchor else idleFraction

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

    // The rail's own width, so its background and labels aren't measured against the
    // narrow touch strip (which clipped the background and wrapped the date pill onto
    // four lines). Only the strip inside it takes pointer input — the rest of this is
    // transparent and non-interactive, so photos underneath stay tappable.
    Box(modifier.fillMaxHeight().width(RAIL_WIDTH)) {
        if (peeking) {
            TimelineRail(scale = scale, trackHeightPx = trackHeightPx, anchor = visualAnchor)
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(HIT_TARGET_WIDTH)
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(scale) {
                    detectFastScrollGesture(
                        // A touch during a peek is landing on a rail that's already on
                        // screen, cursor and all — there's nothing left to disambiguate
                        // from an ordinary swipe the way an invisible strip needs the
                        // long press for, so it can be grabbed immediately. `heldRaw`
                        // isn't part of this check: it can't be true yet, this decides
                        // whether a *new* gesture becomes one.
                        skipConfirmation = { scrollPeekVisible && idlePhoto != null },
                        onStart = { y ->
                            val f = fractionAt(y, trackHeightPx)
                            heldRaw = f
                            // lensAnchor is seeded exactly at the touch point — not
                            // idleFraction — so the very first frame's selection matches
                            // where the finger actually is; anything else measurably
                            // mis-selects (a touch far from the idle position would
                            // otherwise select whatever the lens's compressed far side,
                            // centred on the old idle spot, happens to map it to, rather
                            // than the touched day itself) until the chase below caught
                            // up, which for a short drag might not happen at all.
                            lensAnchor = f
                            // railAnchor, by contrast, starts from wherever the rail was
                            // already showing (idleFraction) and chases lensAnchor exactly
                            // like lensAnchor chases heldRaw — see its own doc above for
                            // why the rendering can afford this lag when selection can't.
                            railAnchor = idleFraction ?: f
                            lastDragNanos = System.nanoTime()
                            releaseHistory.clear()
                            releaseHistory.addLast(lastDragNanos to f)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { y ->
                            val f = fractionAt(y, trackHeightPx)
                            heldRaw = f
                            val now = System.nanoTime()
                            val elapsedMs = (now - lastDragNanos) / 1_000_000f
                            lastDragNanos = now
                            lensAnchor = chaseAnchor(lensAnchor ?: f, f, elapsedMs)
                            railAnchor = chaseAnchor(railAnchor ?: lensAnchor!!, lensAnchor!!, elapsedMs)
                            releaseHistory.addLast(now to lensUnwarp(f, lensAnchor!!))
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
                            settled?.let { onCommit(scale.dayAt(it)) }
                            heldRaw = null
                            lensAnchor = null
                            railAnchor = null
                            lastDragNanos = 0L
                            releaseHistory.clear()
                        },
                    )
                },
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

/** One destination the rail can select. A wrapper rather than a bare `LocalDate?`
 * because `null` means "back to the present" — a real destination, distinct from "not
 * dragging". */
private data class Scrub(val day: LocalDate?)

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

/**
 * The whole library laid out along the track, so the drag has something to aim at.
 *
 * [anchor], when non-null, warps where every tick is *drawn* — [lensWarp]'s own doc — and
 * adds a run of day-level [RailScale.fineTicks] around it: [scale]'s ordinary
 * [RailScale.ticks] are a sparse, whole-library set of ~14 month/year labels, nowhere
 * near fine enough to show what the magnifier has just made room for. The coarse ticks
 * keep drawing everywhere else (also warped, so the whole rail stays one continuous,
 * consistent picture rather than a magnified island stitched onto an unmagnified one).
 *
 * The caller passes an anchor whenever the rail is shown at all, not only while a finger
 * is down — see `TimelineScrollbar`'s own `visualAnchor` (idleFraction while peeking,
 * `railAnchor` — deliberately not the selection-driving `lensAnchor`, see its own doc for
 * why — once held) — so this is magnified around the current idle position even during a
 * plain scroll-triggered peek, not a flat layout that only becomes a lens once held.
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
    anchor: Float? = null,
) {
    val density = LocalDensity.current
    val ticks = remember(scale) { scale.ticks() }
    val fineTicks = remember(scale, anchor) { anchor?.let { scale.fineTicks(it) } ?: emptyList() }

    // Coarse ticks are spaced evenly along the *unwarped* track, which is exactly what
    // the lens's compressed far side ruins: several months' worth of ticks can warp into
    // a handful of pixels and overprint each other. Only warping needs decluttering —
    // the unwarped layout already has each tick its own room — so this is computed once
    // per anchor rather than folded into the loop below.
    val visibleTicks =
        remember(ticks, anchor, trackHeightPx) {
            val a = anchor
            if (a == null) {
                ticks.map { it to it.fraction }
            } else {
                val minGapPx = with(density) { 16.dp.toPx() } / trackHeightPx.coerceAtLeast(1f)
                declutterTicks(ticks, minGapPx) { lensWarp(it, a) }
            }
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
    ) {
        for ((tick, drawnAt) in visibleTicks) {
            val y = with(density) { (drawnAt * trackHeightPx).toDp() }
            Text(
                text = tick.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (tick.major) FontWeight.SemiBold else FontWeight.Normal,
                color =
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (tick.major) 0.85f else 0.45f,
                    ),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = COARSE_TICK_GAP, y = y - 8.dp),
            )
        }
        // Drawn after (so visually on top of) the coarse ticks, and given *more*
        // clearance from the touch strip, not less: these are the labels a finger is
        // actually trying to read while dragging, so they're the ones that most need to
        // sit clear of it rather than tucked in close where a real fingertip covers them.
        for (tick in fineTicks) {
            val y = with(density) { (lensWarp(tick.fraction, anchor!!) * trackHeightPx).toDp() }
            Text(
                text = tick.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (tick.major) FontWeight.Bold else FontWeight.Normal,
                color =
                    (if (tick.major) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        .copy(alpha = if (tick.major) 1f else 0.7f),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = FINE_TICK_GAP, y = y - 8.dp),
            )
        }
    }
}

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
 * beside the finger it was simply covered by it and unreadable. */
@Composable
private fun SelectedDateLabel(
    day: LocalDate?,
    fraction: Float,
    trackHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val y = with(density) { (fraction * trackHeightPx).toDp() }
    Box(
        modifier
            // Unbounded, or the pill is measured against the rail's width and the date
            // wraps one character per line.
            .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
            .offset(x = -(RAIL_WIDTH + 12.dp), y = y - 18.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 4.dp,
        ) {
            Text(
                // Null is the top of the rail, which means "back to the present" rather
                // than any particular date — so it says that instead of naming one.
                text = day?.let(SELECTED_DATE_FORMATTER::format) ?: "Latest",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

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
 * Long-press on the rail, then drag — unless [skipConfirmation] says the rail is already
 * visible (a peek), in which case a touch on the strip is unambiguous and starts the
 * drag immediately. Nothing is consumed until one or the other confirms, so an ordinary
 * swipe that happens to start on the rail scrolls the grid normally —
 * [LazyGridState]'s own `scrollable` modifier sees the same unconsumed events, and this
 * detector just times out having claimed nothing. Everything after confirmation *is*
 * consumed, which is what stops the grid reacting to the same drag.
 *
 * Positions are handed on raw and absolute; the caller maps y to a fraction of the
 * track. There is deliberately no delta accumulation and no gain here.
 */
private suspend fun PointerInputScope.detectFastScrollGesture(
    skipConfirmation: () -> Boolean,
    onStart: (y: Float) -> Unit,
    onDrag: (y: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPosition = down.position

        if (!skipConfirmation()) {
            // Times out (returns null) only if the pointer stayed down, within touch
            // slop, for the full duration — that is the long press. Any early return
            // means the gesture resolved as something else and this must not claim it.
            val abortedEarly =
                withTimeoutOrNull(LONG_PRESS_MS) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                        if (!change.pressed) return@withTimeoutOrNull
                        if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull
                    }
                } != null
            if (abortedEarly) return@awaitEachGesture
        }

        onStart(downPosition.y)
        while (true) {
            val event = awaitPointerEvent()
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

/** The idle thumb's own row: nearest photo at or after [startIndex] (skipping a
 * [TimelineItem.Header], which isn't a photo), read via [LazyPagingItems.peek] so drawing
 * a scrollbar never triggers a page load. The whole entity rather than its `takenAt`,
 * because placing the thumb on a density-weighted rail needs the photo's *day*, and that
 * depends on its own recorded offset too. */
internal fun nearestPhoto(
    items: LazyPagingItems<TimelineItem>,
    startIndex: Int,
): PhotoEntity? {
    for (i in startIndex until minOf(startIndex + 3, items.itemCount)) {
        val item = items.peek(i) ?: continue
        if (item is TimelineItem.Photo) return item.photo
    }
    return null
}

internal fun fractionAt(
    y: Float,
    trackHeightPx: Float,
): Float = if (trackHeightPx <= 0f) 0f else (y / trackHeightPx).coerceIn(0f, 1f)
