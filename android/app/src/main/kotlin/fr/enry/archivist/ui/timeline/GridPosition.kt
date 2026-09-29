package fr.enry.archivist.ui.timeline

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.compose.LazyPagingItems
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import fr.enry.archivist.data.repo.TimelineHistogram
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
 * moves the grid by 1/columns of a row, not a whole row at a time. [lead] is the grid's
 * [leadingCells]: row boundaries fall at `index + lead`, not at `index`.
 *
 * Applied in the *next measure pass* ([LazyGridState.requestScrollToItem]) rather than by a
 * scroll coroutine, and callable from composition. This is what keeps the grid on target
 * while Paging swaps the list underneath it. A coroutine that notices a list change and
 * then scrolls always runs a frame late: the new list has already been drawn once at the
 * old index (or wherever the grid's key-based preservation put it). On a rail jump that
 * was one visible wrong frame per page load while the landing settled, read as "it takes
 * two moves to get there". A request made while composing the new list is measured
 * together with it, so that frame never exists.
 */
internal fun LazyGridState.requestScrollToFractionalIndex(
    index: Float,
    lead: Int,
) {
    val columns = layoutInfo.maxSpan.coerceAtLeast(1)
    val rowHeight = layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
    val row = (index + lead) / columns
    val wholeRow = floor(row).toInt()
    val offset = ((row - wholeRow) * rowHeight).roundToInt()
    requestScrollToItem((wholeRow * columns - lead).coerceAtLeast(0), offset)
}

/**
 * Holds the grid on [target] from composition: re-requests it whenever the target moves
 * *or* the photo at the target index changes (a page loaded above it shifted the list,
 * so the grid's own key-based preservation would otherwise carry it off the target
 * for a frame). Reads [items] through [photoIdAt], so the caller recomposes whenever
 * the list does, which is what puts the request in the same frame as the change.
 */
@Composable
internal fun PinGridTo(
    gridState: LazyGridState,
    target: Float?,
    lead: Int,
    photoIdAt: (Int) -> String?,
) {
    val lastApplied = remember { arrayOfNulls<Any>(1) }
    if (target == null) {
        lastApplied[0] = null
        return
    }
    val key = Triple(target, lead, photoIdAt(floor(target).toInt()))
    if (key != lastApplied[0]) {
        lastApplied[0] = key
        gridState.requestScrollToFractionalIndex(target, lead)
    }
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

/** The fractional item index at the top of the grid: the start of the first visible
 * row, plus how far that row has scrolled off, in items — the inverse of
 * [requestScrollToFractionalIndex]. Row 0's start is `-lead`, the padding before the
 * first photo. */
internal fun LazyGridState.fractionalTopIndex(lead: Int): Float? {
    val first = layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
    val rowHeight = first.size.height
    val columns = layoutInfo.maxSpan.coerceAtLeast(1)
    val scrolled = if (rowHeight <= 0) 0f else firstVisibleItemScrollOffset.toFloat() / rowHeight
    return first.row * columns - lead + scrolled * columns
}

/**
 * How many empty cells come before the first loaded photo, so that every photo's column
 * is its rank in the *whole library* mod [columns] — not its index in the loaded list.
 *
 * The grid wraps rows by position. Placed by list index, every page Paging loaded above
 * the current position (and every window change after a jump) whose size wasn't a
 * multiple of [columns] shifted every photo sideways and re-wrapped every row: the grid
 * visibly re-flowed under the user, and a held rail position landed up to most of a row
 * higher or lower from one page load to the next (logged 2026-09-29: the landing photo's
 * y went −97 → −232 → −164 px as the loaded count went 38 → 40 → 159). With the lead,
 * loading more photos above just changes the lead: nothing already on screen moves.
 *
 * A photo's rank is found at the first day boundary in the list: the first photo of an
 * older day is that day's newest, whose rank [ranks] knows from the histogram. So only the
 * histogram's counts for days *newer* than that boundary matter, and a count that's off
 * (a new upload not yet in the histogram) shifts every rank equally, which still keeps
 * the grid steady. With no histogram, or no day boundary loaded yet, the list is assumed
 * to start at a day's newest photo.
 */
internal fun leadingCells(
    count: Int,
    columns: Int,
    ranks: HistogramRanks?,
    dateAt: (Int) -> LocalDate?,
): Int {
    if (count == 0 || columns <= 1 || ranks == null) return 0
    val firstDay = dateAt(0) ?: return 0
    val rankOfFirst =
        dayRange(count, firstDay, dateAt)?.let { range ->
            val boundary = range.last + 1
            val nextDay = if (boundary < count) dateAt(boundary) else null
            nextDay?.let { ranks.firstRankOf(it) }?.let { it - boundary }
        } ?: ranks.firstRankOf(firstDay) ?: return 0
    return Math.floorMod(rankOfFirst, columns)
}

/** Each day's first (newest) photo's rank in the whole library, newest first, from the
 * histogram — the same running count [DensityScale] builds. */
internal class HistogramRanks(histogram: TimelineHistogram) {
    private val firstRank: Map<LocalDate, Int> =
        buildMap {
            var running = 0
            for ((day, n) in histogram.days.entries.map { LocalDate.parse(it.key) to it.value }.sortedByDescending { it.first }) {
                put(day, running)
                running += n
            }
        }

    fun firstRankOf(day: LocalDate): Int? = firstRank[day]
}
