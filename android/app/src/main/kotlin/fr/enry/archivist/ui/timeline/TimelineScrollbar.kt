package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
 * Idle: a thin thumb whose position is the first visible photo's `takenAt` as a fraction
 * of [TimelineBounds.oldest]..[TimelineBounds.newest] — time, not item count, so it means
 * the same thing whether the library is dense or sparse at that point.
 *
 * Held: the rail expands into a labelled synthesis of the whole library ([timelineTicks])
 * so the target date is visible *before* the finger gets there, and the thumb tracks the
 * finger **absolutely** — the y it is touched at is the point in time it selects. The
 * first version accumulated per-frame deltas through a velocity-dependent gain instead,
 * which meant a full-height drag moved the selection a fraction of the range and the
 * thumb visibly lagged the finger; direct mapping is what "follow my finger" actually
 * requires, and magnifying around the touch point is a separate concern layered on top
 * later, not a substitute for getting this right.
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
    var heldFraction by remember { mutableStateOf<Float?>(null) }

    val idleFraction by remember(items, scale) {
        derivedStateOf {
            nearestPhoto(items, gridState.firstVisibleItemIndex)
                ?.let { scale.fractionOfDay(it.localDate()) }
        }
    }

    val held = heldFraction
    val thumbFraction = held ?: idleFraction ?: 0f

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
    LaunchedEffect(scale) {
        snapshotFlow { heldFraction?.let { Scrub(scale.dayAt(it)) } }
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
        if (held != null) {
            TimelineRail(scale = scale, trackHeightPx = trackHeightPx)
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(HIT_TARGET_WIDTH)
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(scale) {
                    detectFastScrollGesture(
                        onStart = { y ->
                            heldFraction = fractionAt(y, trackHeightPx)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { y -> heldFraction = fractionAt(y, trackHeightPx) },
                        onEnd = {
                            // Always commits, even when the finger settled here long
                            // enough that the window is already loaded: the commit is
                            // also what rebuilds the pager, and that is what clears
                            // PREPEND's latched end-of-pagination so the timeline can be
                            // scrolled back toward the present. The repository skips the
                            // refetch when the day is unchanged, so that costs nothing.
                            heldFraction?.let { onCommit(scale.dayAt(it)) }
                            heldFraction = null
                        },
                    )
                },
        )

        ScrollbarThumb(
            fraction = thumbFraction,
            trackHeightPx = trackHeightPx,
            expanded = held != null,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        if (held != null) {
            SelectedDateLabel(
                day = scale.dayAt(held),
                fraction = held,
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

private val HIT_TARGET_WIDTH = 48.dp
private val RAIL_WIDTH = 96.dp
private val IDLE_THUMB = 4.dp to 40.dp
private val HELD_THUMB = 10.dp to 40.dp

/** The whole library laid out along the track, so the drag has something to aim at. */
@Composable
private fun TimelineRail(
    scale: RailScale,
    trackHeightPx: Float,
) {
    val density = LocalDensity.current
    val ticks = remember(scale) { scale.ticks() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
    ) {
        for (tick in ticks) {
            val y = with(density) { (tick.fraction * trackHeightPx).toDp() }
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
                        .offset(x = 8.dp, y = y - 8.dp),
            )
        }
    }
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
            .padding(end = 4.dp)
            .offset { IntOffset(0, y.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
    )
}

private val SELECTED_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy")

private const val LONG_PRESS_MS = 250L

/**
 * Long-press on the rail, then drag. Nothing is consumed until the press is confirmed,
 * so an ordinary swipe that happens to start on the rail scrolls the grid normally —
 * [LazyGridState]'s own `scrollable` modifier sees the same unconsumed events, and this
 * detector just times out having claimed nothing. Everything after confirmation *is*
 * consumed, which is what stops the grid reacting to the same drag.
 *
 * Positions are handed on raw and absolute; the caller maps y to a fraction of the
 * track. There is deliberately no delta accumulation and no gain here.
 */
private suspend fun PointerInputScope.detectFastScrollGesture(
    onStart: (y: Float) -> Unit,
    onDrag: (y: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPosition = down.position

        // Times out (returns null) only if the pointer stayed down, within touch slop,
        // for the full duration — that is the long press. Any early return means the
        // gesture resolved as something else and this must not claim it.
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
