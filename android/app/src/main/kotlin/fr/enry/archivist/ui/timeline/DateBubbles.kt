package fr.enry.archivist.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

/** One laid-out row as the grid currently has it. [dates] has one entry per cell,
 * `null` for a paging placeholder that hasn't loaded yet. */
internal data class RowGeom(
    val row: Int,
    val topPx: Int,
    val dates: List<LocalDate?>,
)

/** A date bubble to draw at [yPx] (its top edge), for [row]. */
internal data class Bubble(
    val date: LocalDate,
    val yPx: Int,
    val row: Int,
)

/**
 * A row's date: the *latest* local date among its photos, not the first cell's — the list
 * is sorted by UTC `takenAt` but dates are local (`tzOffsetMin`), so the two orders can
 * disagree. `null` if any cell is a not-yet-loaded placeholder: better to show nothing
 * than a wrong date.
 */
internal fun rowDate(cells: List<LocalDate?>): LocalDate? {
    if (cells.isEmpty() || cells.any { it == null }) return null
    return cells.filterNotNull().max()
}

/**
 * Where the floating date bubbles go. Pure, so it is unit-testable without Compose (this
 * repo has no Compose UI test harness) — same convention as [timelineContentState].
 *
 * A row is labelled iff it is row 0 or its date differs from the row above's; a row with
 * an unknown date ([rowDate] null) is never labelled and doesn't make the row below
 * "different". The topmost visible row always gets a bubble, pinned at [stickyTopPx]
 * (like a sticky header) and pushed up by the next labelled row.
 *
 * @param rows visible rows ascending by row, from `layoutInfo`.
 * @param rowAboveFirst the cells of the row just above `rows.first()`, or null when
 *   `rows.first().row == 0` (or it isn't known).
 */
internal fun placeBubbles(
    rows: List<RowGeom>,
    rowAboveFirst: List<LocalDate?>?,
    stickyTopPx: Int,
    bubbleHeightPx: Int,
    gapPx: Int,
    marginPx: Int,
): List<Bubble> {
    if (rows.isEmpty()) return emptyList()
    val dates = rows.map { rowDate(it.dates) }
    val labelled =
        rows.indices.map { i ->
            val date = dates[i] ?: return@map false
            if (rows[i].row == 0) return@map true
            val prev = if (i == 0) rowAboveFirst?.let(::rowDate) else dates[i - 1]
            prev != null && prev != date
        }

    val result = mutableListOf<Bubble>()
    val first = rows.first()
    val firstDate = dates.first()
    if (firstDate != null) {
        val natural = first.topPx + marginPx
        var y = maxOf(natural, stickyTopPx)
        val nextLabelledY = rows.indices.drop(1).firstOrNull { labelled[it] }?.let { rows[it].topPx + marginPx }
        if (nextLabelledY != null) y = minOf(y, nextLabelledY - bubbleHeightPx - gapPx)
        if (y + bubbleHeightPx > 0) result += Bubble(firstDate, y, first.row)
    }
    for (i in 1 until rows.size) {
        if (!labelled[i]) continue
        val y = rows[i].topPx + marginPx
        if (y + bubbleHeightPx > 0) result += Bubble(dates[i]!!, y, rows[i].row)
    }
    return result
}

/**
 * The photo in each of [infos], or null if the grid's last layout and [items] disagree.
 *
 * `layoutInfo` describes the last layout pass; [items] can already hold a newer list.
 * With placeholders disabled, Paging reloading or dropping a page above the viewport
 * shifts every index (confirmed live: 180 -> 176 -> 236 items ~500 ms after a swipe
 * settled, top item at index 84, then 20, then 80, never moving on screen). For the
 * frame in between, `peek(info.index)` is some other photo, and anything built on it
 * showed another row's date. Checking each cell's key catches exactly that frame.
 */
internal fun syncedVisiblePhotos(
    infos: List<LazyGridItemInfo>,
    items: LazyPagingItems<PhotoEntity>,
): List<PhotoEntity>? {
    val count = items.itemCount
    return infos.map { info ->
        val photo = if (info.index < count) items.peek(info.index) else null
        if (photo == null || photo.photoId != info.key) return null
        photo
    }
}

/** How long the bubbles stay after the grid's scroll position last changed. */
internal const val BUBBLE_LINGER_MS = 2000L

private val BUBBLE_HEIGHT = 24.dp
private val BUBBLE_GAP = 4.dp
private val BUBBLE_MARGIN = 8.dp
private val BUBBLE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Floating date bubbles over the grid, visible while its scroll position is changing
 * (flings, drags and rail jumps alike) and [BUBBLE_LINGER_MS] after. Placement is
 * [placeBubbles]; this only gathers the geometry, so it reads items with
 * [LazyPagingItems.peek] only — `items[i]` would trigger page loads from a drawing path.
 * Deliberately has no pointer input, so taps fall through to the photos underneath.
 */
@Composable
internal fun DateBubbleOverlay(
    items: LazyPagingItems<PhotoEntity>,
    gridState: LazyGridState,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        // Keyed on the top item's identity and offset, not firstVisibleItemIndex: Paging
        // reloading or dropping pages above the viewport renumbers every index while
        // nothing on screen moves, and that must not bring the bubbles back.
        // drop(1) (after the first real layout): no flash on first composition. A jump's
        // scrollToItem does change what's on top, so it shows them.
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.key to it.offset.y } }
            .filterNotNull()
            .drop(1)
            .collectLatest {
                lingering = true
                delay(BUBBLE_LINGER_MS)
                lingering = false
            }
    }
    val visible = lingering || gridState.isScrollInProgress

    val density = LocalDensity.current
    val heightPx = with(density) { BUBBLE_HEIGHT.roundToPx() }
    val gapPx = with(density) { BUBBLE_GAP.roundToPx() }
    val marginPx = with(density) { BUBBLE_MARGIN.roundToPx() }
    val stickyTopPx = with(density) { (topInset + BUBBLE_MARGIN).roundToPx() }

    // Only read inside the AnimatedVisibility content below, so nothing is computed
    // while the bubbles are hidden. When the grid's last layout and [items] disagree
    // (see [syncedVisiblePhotos]) the previous placement stands.
    val bubbles by remember(gridState, items, heightPx, gapPx, marginPx, stickyTopPx) {
        var last = emptyList<Bubble>()
        derivedStateOf {
            val infos = gridState.layoutInfo.visibleItemsInfo
            if (infos.isEmpty()) return@derivedStateOf emptyList()
            val photos = syncedVisiblePhotos(infos, items) ?: return@derivedStateOf last
            val dateOf = infos.indices.associate { infos[it].index to photos[it].localDate() }
            val columns = infos.maxOf { it.column } + 1
            val rows =
                infos
                    .groupBy { it.row }
                    .toSortedMap()
                    .map { (row, cells) ->
                        RowGeom(row, cells.first().offset.y, cells.sortedBy { it.index }.map { dateOf[it.index] })
                    }
            val firstRow = rows.first()
            val firstIndex = infos.filter { it.row == firstRow.row }.minOf { it.index }
            // Not on screen, so there's no key to check these against, but the visible
            // cells just proved this snapshot of [items] matches the layout.
            val above =
                if (firstRow.row == 0) {
                    null
                } else {
                    (maxOf(0, firstIndex - columns) until firstIndex).map { i ->
                        if (i < items.itemCount) items.peek(i)?.localDate() else null
                    }
                }
            placeBubbles(rows, above, stickyTopPx, heightPx, gapPx, marginPx).also { last = it }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(300)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            for (bubble in bubbles) {
                key(bubble.row) {
                    Box(
                        Modifier
                            .offset { IntOffset(marginPx, bubble.yPx) }
                            .height(BUBBLE_HEIGHT)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            bubble.date.format(BUBBLE_FORMATTER),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }
    }
}
