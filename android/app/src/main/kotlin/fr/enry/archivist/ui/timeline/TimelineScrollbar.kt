package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import fr.enry.archivist.data.repo.TimelineBounds
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Replaces the platform's default `LazyVerticalGrid` scroll indicator, which
 * `docs/plans/STATUS.md`'s 2026-09-10 entry traces to
 * `LazyGridLayoutInfo.calculateContentSize()` (Compose Foundation 1.10.0): it estimates
 * total content size from the *currently visible* lines' average main-axis size,
 * extrapolated across every line — a poor estimate once a grid mixes full-width date
 * headers with square photo cells, since that average shifts depending on how many
 * headers happen to be on screen right now. That's confirmed from Foundation's own
 * decompiled source, not guesswork, and there's no public API to suppress it — this
 * composable just draws over the same screen region with something driven by real
 * timestamps instead.
 *
 * Two states, same visual thumb:
 * - **Idle**: position tracks the first visible photo's `takenAt` as a fraction of
 *   [TimelineBounds.oldest]..[TimelineBounds.newest] — this alone is what makes the
 *   thumb move proportionally to elapsed time instead of sitting at a fixed fraction.
 * - **Fast-scroll**: a long-press-and-drag on the hit-target strip enters drag mode
 *   (haptic, thumb expands, a loupe with the selected date appears), tracks the drag
 *   with [applyFastScrollGain]'s velocity-gated dual gain, and on release calls [onJump]
 *   with the selected instant. The caller (`TimelineScreen`) is responsible for actually
 *   triggering the `REFRESH` (`LazyPagingItems.refresh()`) and scrolling to the top of
 *   the newly-loaded window — this composable only reports the target.
 */
@Composable
fun TimelineScrollbar(
    gridState: LazyGridState,
    items: LazyPagingItems<TimelineItem>,
    bounds: TimelineBounds?,
    onJump: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing meaningful to show without a real time range -- rather than fall back to
    // the old (broken) index-based guess, this just lets the platform's own indicator
    // show through until the range loads, same as before this feature existed.
    if (bounds == null) return

    val haptics = LocalHapticFeedback.current
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var isFastScrolling by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val idleFraction by remember(items, bounds) {
        derivedStateOf {
            nearestPhotoTakenAt(items, gridState.firstVisibleItemIndex)
                ?.let { fractionAtInstant(Instant.parse(it), bounds) }
        }
    }

    val thumbFraction = if (isFastScrolling) dragFraction else (idleFraction ?: 0f)

    Box(
        modifier
            .fillMaxHeight()
            .width(FAST_SCROLL_HIT_TARGET_WIDTH)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(bounds) {
                detectFastScrollGesture(
                    trackHeightPx = { trackHeightPx },
                    startFraction = { idleFraction ?: 0f },
                    onStart = { fraction ->
                        isFastScrolling = true
                        dragFraction = fraction
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { fraction -> dragFraction = fraction },
                    onEnd = { committed ->
                        if (committed) onJump(instantAtFraction(dragFraction, bounds))
                        isFastScrolling = false
                    },
                )
            },
    ) {
        ScrollbarThumb(
            fraction = thumbFraction,
            trackHeightPx = trackHeightPx,
            expanded = isFastScrolling,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        if (isFastScrolling) {
            FastScrollLoupe(
                selectedInstant = instantAtFraction(dragFraction, bounds),
                fraction = dragFraction,
                trackHeightPx = trackHeightPx,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

private val FAST_SCROLL_HIT_TARGET_WIDTH = 48.dp
private val IDLE_THUMB_SIZE_DP = 4.dp to 32.dp
private val EXPANDED_THUMB_SIZE_DP = 10.dp to 48.dp

@Composable
private fun ScrollbarThumb(
    fraction: Float,
    trackHeightPx: Float,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val (widthDp, heightDp) = if (expanded) EXPANDED_THUMB_SIZE_DP else IDLE_THUMB_SIZE_DP
    val heightPx = with(LocalDensity.current) { heightDp.toPx() }
    val y = (fraction * (trackHeightPx - heightPx)).coerceAtLeast(0f)
    Box(
        modifier
            .padding(end = 4.dp)
            .offset { IntOffset(0, y.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
    )
}

/** Offsets (seconds from the centered instant) the loupe's ruler shows — a small, fixed
 * set of calendar-meaningful distances rather than a continuous axis, since a tick every
 * few pixels would be unreadable text at this size. [logTickOffsetPx] spaces them: close
 * ones (a day) spread out for precision, far ones (a year) compress — the "magnified
 * area around the finger" the feature was asked for. */
private val LOUPE_TICK_OFFSETS_SECONDS = listOf(-365L * 86400, -30L * 86400, -7L * 86400, -86400L, 0L, 86400L, 7L * 86400, 30L * 86400, 365L * 86400)
private val LOUPE_TICK_SCALE_PX_PER_LOG_UNIT = 22f

@Composable
private fun FastScrollLoupe(
    selectedInstant: Instant,
    fraction: Float,
    trackHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val yPx = fraction * trackHeightPx
    val y = with(density) { yPx.toDp() }
    Box(modifier.offset(x = (-96).dp, y = y)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
        ) {
            Box(Modifier.padding(vertical = 8.dp)) {
                for (offsetSeconds in LOUPE_TICK_OFFSETS_SECONDS) {
                    val tickInstant = selectedInstant.plusSeconds(offsetSeconds)
                    val offsetPx = logTickOffsetPx(offsetSeconds, LOUPE_TICK_SCALE_PX_PER_LOG_UNIT)
                    val offsetDp = with(density) { offsetPx.toDp() }
                    Text(
                        text = LOUPE_DATE_FORMATTER.format(tickInstant.atZone(ZoneOffset.UTC)),
                        style = if (offsetSeconds == 0L) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        color =
                            if (offsetSeconds == 0L) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            },
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = 12.dp, y = offsetDp)
                                .padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

private val LOUPE_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy")

private const val LONG_PRESS_MS = 250L
internal const val FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S = 2000f
internal const val FAST_SCROLL_FINE_GAIN = 0.15f

/**
 * Long-press (no more than the platform's own touch slop of movement for
 * [LONG_PRESS_MS]) on the hit-target strip enters fast-scroll; until then nothing is
 * consumed, so an ordinary fast swipe that happens to start within the strip is
 * untouched — [LazyGridState]'s own `scrollable` modifier sees the same unconsumed
 * events and scrolls normally, and this detector simply times out having claimed
 * nothing. Once confirmed, every subsequent pointer event *is* consumed, which is what
 * stops the grid from also reacting to the same drag.
 */
private suspend fun PointerInputScope.detectFastScrollGesture(
    trackHeightPx: () -> Float,
    startFraction: () -> Float,
    onStart: (fraction: Float) -> Unit,
    onDrag: (fraction: Float) -> Unit,
    onEnd: (committed: Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPosition = down.position

        // Times out (returns null) only if the pointer is still down, still within
        // touch slop, for the full duration -- see the class doc above for why an
        // early return (still-Unit-typed) means "not a long press" instead.
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

        var fraction = startFraction()
        onStart(fraction)
        var lastPosition = downPosition
        var lastUptimeMs = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                onEnd(true)
                break
            }
            val dtSeconds = ((change.uptimeMillis - lastUptimeMs).coerceAtLeast(1)) / 1000f
            val deltaPx = change.position.y - lastPosition.y
            val velocityPxPerSec = deltaPx / dtSeconds
            fraction = applyFastScrollGain(fraction, deltaPx, trackHeightPx(), velocityPxPerSec)
            onDrag(fraction)
            lastPosition = change.position
            lastUptimeMs = change.uptimeMillis
            change.consume()
        }
    }
}

/** The idle thumb's own row: nearest photo at or after [startIndex] (skipping a
 * [TimelineItem.Header], which has no `takenAt` of its own), read via
 * [LazyPagingItems.peek] so this never triggers a placeholder load just to draw a
 * scrollbar position. `null` when nothing nearby is loaded yet (e.g. right after a
 * jump, before the new page has composed). */
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

/** Fraction along the full track -> the [Instant] it represents. `0f` is
 * [TimelineBounds.newest] (top of the newest-first grid), `1f` is
 * [TimelineBounds.oldest] (bottom) — matches [TimelineScreen]'s own top-to-bottom,
 * newest-to-oldest order. */
internal fun instantAtFraction(
    fraction: Float,
    bounds: TimelineBounds,
): Instant {
    val clamped = fraction.coerceIn(0f, 1f)
    val totalMillis = bounds.newest.toEpochMilli() - bounds.oldest.toEpochMilli()
    // Double, not Float, for this multiplication: a multi-year range in milliseconds
    // (order 1e11-1e12) already exceeds a Float's ~7-significant-digit precision, which
    // silently rounded fraction=1f short of bounds.oldest by a visible amount --
    // confirmed live, not hypothetically (see TimelineScrollbarTest).
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
 * Velocity-gated dual gain — the "fast = coarse, slow = precise" behavior the fast-scroll
 * gesture is for, without a stateful recursive lens model: a fast drag (at or above
 * [FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S]) moves the selected fraction 1:1 with the
 * finger, covering the whole library in one swipe; a slow drag moves it at
 * [FAST_SCROLL_FINE_GAIN] instead, so small, deliberate finger movements resolve to
 * small time deltas even when the library spans years. [trackHeightPx] of `0` (not yet
 * measured) is a no-op rather than a divide-by-zero.
 */
internal fun applyFastScrollGain(
    currentFraction: Float,
    deltaPx: Float,
    trackHeightPx: Float,
    velocityPxPerSec: Float,
): Float {
    if (trackHeightPx <= 0f) return currentFraction
    val gain = if (abs(velocityPxPerSec) >= FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S) 1f else FAST_SCROLL_FINE_GAIN
    val deltaFraction = (deltaPx / trackHeightPx) * gain
    return (currentFraction + deltaFraction).coerceIn(0f, 1f)
}

/** Log-scale screen offset for a loupe tick this many seconds from the centered instant
 * — nearby ticks spread out, distant ones compress, the "magnified area around the
 * finger" the feature was asked for. Used by [FastScrollLoupe]'s ruler; pulled out as a
 * pure function so the spacing math has JVM-only test coverage independent of Compose. */
internal fun logTickOffsetPx(
    secondsFromCenter: Long,
    scalePxPerLogUnit: Float,
): Float {
    if (secondsFromCenter == 0L) return 0f
    val sign = if (secondsFromCenter > 0) 1f else -1f
    return sign * scalePxPerLogUnit * ln(1f + abs(secondsFromCenter).toFloat() / 60f)
}
