package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.paging.compose.LazyPagingItems
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Turning a [RailPosition] into somewhere in the grid, finer than "the top of that day".
 *
 * Scrolling to each day's first photo is what made dragging the rail feel like a series
 * of snaps: the grid stood still while the finger crossed a day, then jumped a whole
 * day's worth of photos at once as it crossed into the next. Placing the grid at the
 * same fraction of the day's *photos* as the finger is of the day's *track* instead
 * means it moves continuously, and — with the density scale, where each photo is an
 * equal slice of the track — at a constant rate per pixel of finger movement.
 */

/** Where [day]'s photos start and end in a newest-first list of [count] photos, found by
 * binary search on [dateAt] — `null` if none of [day] is in the list. End is exclusive. */
internal fun dayRange(
    count: Int,
    day: LocalDate,
    dateAt: (Int) -> LocalDate?,
): IntRange? {
    // First index whose date is on or before `bound` — the list is newest first, so
    // "on or before" is monotonic along it. A null date (shouldn't happen with
    // placeholders off) is treated as older, which keeps the search well-defined.
    fun firstAtOrBefore(bound: LocalDate): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val d = dateAt(mid)
            if (d == null || d <= bound) hi = mid else lo = mid + 1
        }
        return lo
    }
    val start = firstAtOrBefore(day)
    if (start >= count || dateAt(start) != day) return null
    val end = firstAtOrBefore(day.minusDays(1))
    return start until end
}

/**
 * The fractional item index [position] names, given that [day][RailPosition.day]'s photos
 * start at [first] and [loadedInDay] of them are currently loaded. Uses the scale's own
 * [RailPosition.dayCount] when it has one, so a day only partly paged in still puts the
 * grid where the finger says rather than squeezing the whole day into what's loaded —
 * the clamp to [itemCount] then just waits for paging to catch up.
 */
internal fun dayTargetIndex(
    first: Int,
    loadedInDay: Int,
    position: RailPosition,
    itemCount: Int,
): Float {
    val count = position.dayCount ?: loadedInDay
    val target = first + position.progress * count
    return target.coerceIn(0f, (itemCount - 1).coerceAtLeast(0).toFloat())
}

/** [dayTargetIndex] for [position], resolved against whatever [items] currently holds;
 * `null` when [position]'s day isn't loaded (yet). */
internal fun targetIndexFor(
    items: LazyPagingItems<PhotoEntity>,
    position: RailPosition,
): Float? {
    val day = position.day ?: return 0f
    val count = items.itemCount
    val range = dayRange(count, day) { i -> items.peek(i)?.localDate() } ?: return null
    return dayTargetIndex(range.first, range.last - range.first + 1, position, count)
}

/**
 * Scrolls so the row holding fractional item [index] sits at the top, offset by the
 * fraction of a row [index] is past that row's start — so moving [index] by one photo
 * moves the grid by 1/columns of a row, not a whole row at a time.
 */
internal suspend fun LazyGridState.scrollToFractionalIndex(index: Float) {
    val columns = layoutInfo.maxSpan.coerceAtLeast(1)
    val rowHeight = layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
    val row = index / columns
    val wholeRow = floor(row).toInt()
    val offset = ((row - wholeRow) * rowHeight).roundToInt()
    scrollToItem(wholeRow * columns, offset)
}

/**
 * The inverse of [dayTargetIndex]: how far through its day fractional item [index] is,
 * when that day's photos start at [first] with [loadedInDay] loaded. What keeps the idle
 * thumb moving continuously as the grid scrolls, instead of stepping once per day.
 */
internal fun progressAt(
    index: Float,
    first: Int,
    loadedInDay: Int,
    dayCount: Int?,
): Float {
    val count = dayCount ?: loadedInDay
    return if (count <= 0) 0f else ((index - first) / count).coerceIn(0f, 1f)
}

/** The fractional item index at the top of the grid: the first visible row's first
 * item, plus how far that row has scrolled off, in items. */
internal fun LazyGridState.fractionalTopIndex(): Float? {
    val first = layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
    val rowHeight = first.size.height
    val columns = layoutInfo.maxSpan.coerceAtLeast(1)
    val scrolled = if (rowHeight <= 0) 0f else firstVisibleItemScrollOffset.toFloat() / rowHeight
    return first.index + scrolled * columns
}
