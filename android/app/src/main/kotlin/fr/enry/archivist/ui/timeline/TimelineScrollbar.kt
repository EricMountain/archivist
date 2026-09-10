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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import fr.enry.archivist.data.repo.TimelineBounds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
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
    onJump: (Instant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bounds == null) return

    val haptics = LocalHapticFeedback.current
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var heldFraction by remember { mutableStateOf<Float?>(null) }

    val idleFraction by remember(items, bounds) {
        derivedStateOf {
            nearestPhotoTakenAt(items, gridState.firstVisibleItemIndex)
                ?.let { fractionAtInstant(Instant.parse(it), bounds) }
        }
    }

    val held = heldFraction
    val thumbFraction = held ?: idleFraction ?: 0f

    // The rail's own width, so its background and labels aren't measured against the
    // narrow touch strip (which clipped the background and wrapped the date pill onto
    // four lines). Only the strip inside it takes pointer input — the rest of this is
    // transparent and non-interactive, so photos underneath stay tappable.
    Box(modifier.fillMaxHeight().width(RAIL_WIDTH)) {
        if (held != null) {
            TimelineRail(bounds = bounds, trackHeightPx = trackHeightPx)
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(HIT_TARGET_WIDTH)
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(bounds) {
                    detectFastScrollGesture(
                        onStart = { y ->
                            heldFraction = fractionAt(y, trackHeightPx)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { y -> heldFraction = fractionAt(y, trackHeightPx) },
                        onEnd = {
                            heldFraction?.let { onJump(jumpTargetFor(it, bounds)) }
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
                instant = instantAtFraction(held, bounds),
                fraction = held,
                trackHeightPx = trackHeightPx,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

private val HIT_TARGET_WIDTH = 48.dp
private val RAIL_WIDTH = 96.dp
private val IDLE_THUMB = 4.dp to 40.dp
private val HELD_THUMB = 10.dp to 40.dp

/** The whole library laid out along the track, so the drag has something to aim at. */
@Composable
private fun TimelineRail(
    bounds: TimelineBounds,
    trackHeightPx: Float,
) {
    val density = LocalDensity.current
    val ticks = remember(bounds) { timelineTicks(bounds) }

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
    instant: Instant,
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
                text = SELECTED_DATE_FORMATTER.format(instant.atZone(ZoneId.systemDefault())),
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
 * [TimelineItem.Header], which has no `takenAt` of its own), read via
 * [LazyPagingItems.peek] so drawing a scrollbar never triggers a page load. */
internal fun nearestPhotoTakenAt(
    items: LazyPagingItems<TimelineItem>,
    startIndex: Int,
): String? {
    for (i in startIndex until minOf(startIndex + 3, items.itemCount)) {
        val item = items.peek(i) ?: continue
        if (item is TimelineItem.Photo) return item.photo.takenAt
    }
    return null
}

internal fun fractionAt(
    y: Float,
    trackHeightPx: Float,
): Float = if (trackHeightPx <= 0f) 0f else (y / trackHeightPx).coerceIn(0f, 1f)

/** Fraction along the track -> the [Instant] it represents. `0f` is
 * [TimelineBounds.newest] (top of the newest-first grid), `1f` is
 * [TimelineBounds.oldest]. */
internal fun instantAtFraction(
    fraction: Float,
    bounds: TimelineBounds,
): Instant {
    val clamped = fraction.coerceIn(0f, 1f)
    val totalMillis = bounds.newest.toEpochMilli() - bounds.oldest.toEpochMilli()
    // Double, not Float: a multi-year range in milliseconds (order 1e11) already exceeds
    // a Float's ~7 significant digits, which rounded fraction=1f visibly short of oldest.
    return bounds.newest.minusMillis((totalMillis * clamped.toDouble()).toLong())
}

/** The inverse of [instantAtFraction], for the idle thumb. */
internal fun fractionAtInstant(
    instant: Instant,
    bounds: TimelineBounds,
): Float {
    val totalMillis = (bounds.newest.toEpochMilli() - bounds.oldest.toEpochMilli()).coerceAtLeast(1)
    val elapsed = bounds.newest.toEpochMilli() - instant.toEpochMilli()
    return (elapsed.toDouble() / totalMillis).toFloat().coerceIn(0f, 1f)
}

/**
 * Null — meaning "back to the present", an unbounded refresh — for a release at the very
 * top of the track, rather than a bounded window at `newest`. Two reasons: the server's
 * `to` bound is exclusive of photos at exactly that instant (`timelineSk` is
 * `<takenAt>#<photoId>`, so a bare timestamp sorts before every real key at it, per
 * sample-data.md), which would drop the newest photo; and it makes the top of the rail
 * the reliable way back to a normal, present-anchored timeline after a jump.
 */
internal fun jumpTargetFor(
    fraction: Float,
    bounds: TimelineBounds,
): Instant? = if (fraction <= TOP_OF_RAIL_FRACTION) null else instantAtFraction(fraction, bounds)

private const val TOP_OF_RAIL_FRACTION = 0.01f

/** One label on the fast-scroll rail. [fraction] is its position along the track. */
internal data class TimelineTick(
    val fraction: Float,
    val label: String,
    val major: Boolean,
)

/**
 * The whole library as a handful of labelled points — years for a long library, months
 * for a short one — so a drag can be aimed rather than guessed at. Granularity adapts to
 * the span because both extremes are useless: month labels across fifteen years are an
 * unreadable smear, and year labels across eight months are a single tick.
 *
 * Positions are linear in *time*, matching [instantAtFraction] exactly — a rail whose
 * labels didn't agree with where a drag actually lands would be worse than none.
 */
internal fun timelineTicks(
    bounds: TimelineBounds,
    zone: ZoneId = ZoneId.systemDefault(),
    maxTicks: Int = 14,
): List<TimelineTick> {
    val oldest = bounds.oldest.atZone(zone)
    val newest = bounds.newest.atZone(zone)
    if (!oldest.isBefore(newest)) return emptyList()

    val months = ChronoUnit.MONTHS.between(oldest.withDayOfMonth(1), newest.withDayOfMonth(1)).toInt() + 1
    val stepMonths = TICK_STEPS_MONTHS.firstOrNull { months / it <= maxTicks } ?: TICK_STEPS_MONTHS.last()
    val yearOnly = stepMonths >= 12

    // Labels land on round dates (a January, or a quarter start) rather than wherever
    // the library happens to begin — the point is a legible scale, not an exact
    // reproduction of the range's endpoints.
    val alignTo = if (yearOnly) 12 else stepMonths
    var cursor = oldest.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
    while ((cursor.monthValue - 1) % alignTo != 0) cursor = cursor.plusMonths(1)
    while (cursor.isBefore(oldest)) cursor = cursor.plusMonths(stepMonths.toLong())

    val ticks = mutableListOf<TimelineTick>()
    while (!cursor.isAfter(newest)) {
        ticks +=
            TimelineTick(
                fraction = fractionAtInstant(cursor.toInstant(), bounds),
                label = if (yearOnly) cursor.year.toString() else MONTH_TICK_FORMATTER.format(cursor),
                major = if (yearOnly) cursor.year % 5 == 0 else cursor.monthValue == 1,
            )
        cursor = cursor.plusMonths(stepMonths.toLong())
    }

    // A library spanning less than one step boundary would otherwise draw an empty rail,
    // which reads as broken rather than as "there's not much here".
    if (ticks.size < 2) {
        return listOf(
            TimelineTick(0f, MONTH_TICK_FORMATTER.format(newest), major = true),
            TimelineTick(1f, MONTH_TICK_FORMATTER.format(oldest), major = true),
        )
    }
    // Top-down, i.e. newest first and fraction ascending — the order the rail is read in,
    // and the same order as the grid beside it. Generation walks the other way because
    // it has to start from a round boundary at the old end.
    return ticks.asReversed()
}

/** Month steps to try, coarsening until the whole range fits in `maxTicks` labels. */
private val TICK_STEPS_MONTHS = listOf(1, 2, 3, 6, 12, 24, 60, 120)

private val MONTH_TICK_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy")
