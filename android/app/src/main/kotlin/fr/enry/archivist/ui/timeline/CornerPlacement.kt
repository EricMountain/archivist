package fr.enry.archivist.ui.timeline

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A rounded screen corner (platform `RoundedCorner`): its arc's radius and centre, window px. */
internal data class CornerArc(
    val radius: Int,
    val centerX: Int,
    val centerY: Int,
)

/** A display cutout's bounding rectangle, window px; `right` and `bottom` are exclusive. */
internal data class CutoutRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** What the top band is laid out against: the window's width, its two top corners (null
 * when the platform reports none: API < 31, or a square corner) and its cutouts. */
internal data class TopCornerGeometry(
    val windowWidth: Int,
    val topLeft: CornerArc?,
    val topRight: CornerArc?,
    val cutouts: List<CutoutRect>,
)

/** Where a top-band element goes: its bounding box's distance from the side edge it hugs
 * (left or right, physical) and from the top edge, px. */
internal data class CornerOffset(
    val side: Int,
    val top: Int,
)

/**
 * Places an element in a top corner of the window, as close to it as is safe: the `⋮`
 * button, the filter chip and the menu card.
 *
 * The element's own corner on that side is a circle of radius `q` = [roundness] (half
 * the height for the button and the pill-shaped chip, the card's corner radius for the
 * card); the rest of the element runs away from the corner, so only that circle can
 * collide with the screen's rounding. A circle of radius `q` with [margin] `m` to spare
 * fits inside the screen's corner arc (radius `r`, centre `C`) when its centre `P` lies
 * in the screen eroded by `s = q + m`: a rounded rectangle whose corner arc has the same
 * centre `C` and radius `ρ = r − s`. So, with `dx`, `dy` the distances of `C` from the
 * side and top edges (both `r` on a phone whose corner is a plain quarter circle):
 *
 * * if `ρ ≤ 0` (a corner tighter than the element, or no corner at all), the corner
 *   doesn't constrain it: `P` is `s` from both edges, so the box is `m` from both;
 * * otherwise `P` goes on the 45° line from `C` towards the corner, on the eroded arc:
 *   `P = (max(s, dx − ρ/√2), max(s, dy − ρ/√2))` from the (side, top) edges, i.e.
 *   `|P − C| = ρ = r − q − m`. For a quarter-circle corner this is
 *   `r − (r − s)/√2` from each edge, and the box is that minus `q`.
 *
 * Then the top is held below [minTop] + `m` (a status bar that's showing), the side
 * beyond [minSide] + `m`, and if the box (grown by `m`) meets a cutout it drops below
 * the lowest such cutout, + `m`. A box that has dropped needs the corner less, so its
 * side is recomputed for its new top ([sideAt]) and the cutouts re-checked. [width] is
 * a function of the side because the card and chip fill the band between their sides.
 */
internal fun placeInTopCorner(
    geometry: TopCornerGeometry,
    atRight: Boolean,
    width: (side: Int) -> Int,
    height: Int,
    roundness: Float,
    margin: Int,
    minTop: Int = 0,
    minSide: Int = 0,
): CornerOffset {
    val arc = if (atRight) geometry.topRight else geometry.topLeft
    val q = roundness
    val s = q + margin
    val rho = if (arc == null) 0f else arc.radius - s
    val floorTop = minTop + margin
    val floorSide = minSide + margin

    var top =
        if (arc == null || rho <= 0f) {
            margin
        } else {
            ceil(max(s, arc.centerY - rho / SQRT2) - q).toInt()
        }
    top = max(top, floorTop)
    var side = max(floorSide, sideAt(geometry, atRight, top, q, margin))

    // Each pass either settles or drops below a cutout it hadn't cleared yet.
    repeat(geometry.cutouts.size + 1) {
        val w = width(side)
        val left = if (atRight) geometry.windowWidth - side - w else side
        val hit =
            geometry.cutouts
                .filter { it.left < left + w + margin && it.right > left - margin && it.top < top + height + margin && it.bottom > top - margin }
                .maxOfOrNull { it.bottom } ?: return CornerOffset(side, top)
        top = hit + margin
        side = max(floorSide, sideAt(geometry, atRight, top, q, margin))
    }
    return CornerOffset(side, top)
}

/**
 * The filter chip (F8): in the top-start corner, on the button's row (never above it),
 * between the start edge and the button ([button], `buttonSize` wide, [margin] apart).
 * A label too long to fit on that row beside a cutout drops below the cutout instead,
 * where it may use the band's whole width. Returns the chip's offset and its width (at
 * most [desiredWidth], its unconstrained width).
 */
internal fun placeChip(
    geometry: TopCornerGeometry,
    atRight: Boolean,
    desiredWidth: Int,
    height: Int,
    button: CornerOffset,
    buttonSize: Int,
    margin: Int,
    minSide: Int = 0,
): Pair<CornerOffset, Int> {
    fun widthAt(side: Int) = min(desiredWidth, geometry.windowWidth - side - (button.side + buttonSize + margin)).coerceAtLeast(0)
    val offset =
        placeInTopCorner(
            geometry,
            atRight,
            ::widthAt,
            height,
            roundness = height / 2f,
            margin = margin,
            // Not above the button: on a phone with matching corners this is its row.
            minTop = button.top - margin,
            minSide = minSide,
        )
    return offset to widthAt(offset.side)
}

/** [placeInTopCorner] for a fixed-width element. */
internal fun placeInTopCorner(
    geometry: TopCornerGeometry,
    atRight: Boolean,
    width: Int,
    height: Int,
    roundness: Float,
    margin: Int,
    minTop: Int = 0,
    minSide: Int = 0,
): CornerOffset = placeInTopCorner(geometry, atRight, { width }, height, roundness, margin, minTop, minSide)

/**
 * The smallest distance from the side edge at which an element whose corner circle has
 * radius [q] fits, [margin] clear of the screen's corner arc, with its top at [top]: the
 * circle's centre `P` must be within `ρ = r − q − m` of the arc's centre `C`, so
 * `P.x ≥ dx − √(ρ² − (dy − P.y)²)` while `P.y < dy`, and just `s = q + m` once it is
 * level with or below `C`.
 */
internal fun sideAt(
    geometry: TopCornerGeometry,
    atRight: Boolean,
    top: Int,
    q: Float,
    margin: Int,
): Int {
    val arc = (if (atRight) geometry.topRight else geometry.topLeft) ?: return margin
    val s = q + margin
    val rho = arc.radius - s
    val dx = if (atRight) geometry.windowWidth - arc.centerX else arc.centerX
    val dy = arc.centerY
    val cy = top + q
    if (rho <= 0f || cy >= dy) return margin
    val below = dy - cy
    // Above the eroded arc's top: nothing fits at this height; hug the arc's centre line.
    val reach = if (below >= rho) 0f else sqrt(rho * rho - below * below)
    return ceil(max(s, dx - reach) - q).toInt()
}

private val SQRT2 = sqrt(2f)

/**
 * The window's top corners and cutouts, read from the root view's platform insets. The
 * Compose cutout insets are read too, so that a change to them (rotation, a new window)
 * recomposes this. Without platform insets (not attached yet), the whole strip above the
 * Compose cutout inset counts as cutout — the old, conservative placement.
 */
@Composable
internal fun rememberTopCornerGeometry(windowWidth: Int): TopCornerGeometry {
    val view = LocalView.current
    val density = LocalDensity.current
    val cutoutInsets = WindowInsets.displayCutout
    val cutoutTop = cutoutInsets.getTop(density)
    val cutoutLeft = cutoutInsets.getLeft(density, LayoutDirection.Ltr)
    val cutoutRight = cutoutInsets.getRight(density, LayoutDirection.Ltr)
    val configuration = LocalConfiguration.current
    return remember(view, windowWidth, cutoutTop, cutoutLeft, cutoutRight, configuration.orientation) {
        val insets = view.rootWindowInsets
        if (insets == null) {
            TopCornerGeometry(windowWidth, null, null, if (cutoutTop > 0) listOf(CutoutRect(0, 0, windowWidth, cutoutTop)) else emptyList())
        } else {
            val cutouts =
                insets.displayCutout
                    ?.boundingRects
                    .orEmpty()
                    .filter { !it.isEmpty }
                    .map { CutoutRect(it.left, it.top, it.right, it.bottom) }
            fun corner(position: Int): CornerArc? {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
                val c = insets.getRoundedCorner(position) ?: return null
                return if (c.radius > 0) CornerArc(c.radius, c.center.x, c.center.y) else null
            }
            TopCornerGeometry(
                windowWidth = windowWidth,
                topLeft = corner(RoundedCorner.POSITION_TOP_LEFT),
                topRight = corner(RoundedCorner.POSITION_TOP_RIGHT),
                cutouts = cutouts,
            )
        }
    }
}
