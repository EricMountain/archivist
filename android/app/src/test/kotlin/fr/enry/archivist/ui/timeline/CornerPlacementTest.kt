package fr.enry.archivist.ui.timeline

import kotlin.math.hypot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The top band's corner placement: `⋮`, the chip and the menu card (placeInTopCorner). */
class CornerPlacementTest {
    // A 1080 px wide phone at 2.625 px/dp: 40 dp button, 8 dp margin, 16 dp card corner.
    private val width = 1080
    private val button = 105
    private val margin = 21
    private val cardCorner = 42f

    /** Quarter-circle corners of radius [r] on both sides, and [cutouts]. */
    private fun phone(
        r: Int?,
        cutouts: List<CutoutRect> = emptyList(),
    ) = TopCornerGeometry(
        windowWidth = width,
        topLeft = r?.let { CornerArc(it, it, it) },
        topRight = r?.let { CornerArc(it, width - it, it) },
        cutouts = cutouts,
    )

    /** A centred punch hole, as on the emulated phone: 486..596 × 0..121. */
    private val punchHole = CutoutRect(486, 0, 596, 121)

    private fun button(
        g: TopCornerGeometry,
        atRight: Boolean = true,
        minTop: Int = 0,
    ) = placeInTopCorner(g, atRight, button, button, button / 2f, margin, minTop = minTop)

    /** Whether a corner circle of radius [q] at [o] keeps [m] clear of [g]'s corner arc. */
    private fun fits(
        g: TopCornerGeometry,
        atRight: Boolean,
        o: CornerOffset,
        q: Float,
        m: Int,
    ): Boolean {
        if (o.side < m || o.top < m) return false
        val arc = (if (atRight) g.topRight else g.topLeft) ?: return true
        val dx = if (atRight) g.windowWidth - arc.centerX else arc.centerX
        val px = o.side + q
        val py = o.top + q
        if (px >= dx || py >= arc.centerY) return true
        return hypot(dx - px, arc.centerY - py) <= arc.radius - q - m + 1e-3
    }

    @Test
    fun `the emulated phone's 128 px corner puts the button 37 px from both edges`() {
        // s = 52.5 + 21 = 73.5, ρ = 128 − 73.5 = 54.5; centre 128 − 54.5/√2 = 89.46 from
        // each edge, box 89.46 − 52.5 = 36.96 → 37.
        val g = phone(128, listOf(punchHole))
        assertEquals(CornerOffset(37, 37), button(g))
        assertTrue(fits(g, true, CornerOffset(37, 37), button / 2f, margin))
        // A pixel further into the corner on the diagonal no longer keeps the margin.
        assertFalse(fits(g, true, CornerOffset(35, 35), button / 2f, margin))
    }

    @Test
    fun `the placement is mirrored for the top-left corner`() {
        val g = phone(128, listOf(punchHole))
        assertEquals(button(g, atRight = true), button(g, atRight = false))
        // Only the left corner rounded: the right side falls back to the plain margin.
        val leftOnly = g.copy(topRight = null)
        assertEquals(CornerOffset(37, 37), button(leftOnly, atRight = false))
        assertEquals(CornerOffset(margin, margin), button(leftOnly, atRight = true))
    }

    @Test
    fun `no corner information, or a corner tighter than the button, means the plain margin`() {
        assertEquals(CornerOffset(margin, margin), button(phone(null)))
        assertEquals(CornerOffset(margin, margin), button(phone(0)))
        // r ≤ q + m: the corner can't reach the button.
        assertEquals(CornerOffset(margin, margin), button(phone(73)))
    }

    @Test
    fun `larger corners push the button further in, always just inside the arc`() {
        var last = 0
        for (r in listOf(80, 100, 128, 160, 200, 300)) {
            val g = phone(r)
            val o = button(g)
            assertTrue(kotlin.math.abs(o.side - o.top) <= 1, "r=$r: $o")
            assertTrue(o.side >= last, "r=$r")
            assertTrue(fits(g, true, o, button / 2f, margin), "r=$r")
            assertFalse(fits(g, true, CornerOffset(o.side - 2, o.top - 2), button / 2f, margin), "r=$r")
            last = o.side
        }
    }

    @Test
    fun `a smaller element's centre sits deeper in the same corner`() {
        // Its box's edge sits further out, though: a rounder box hugs a round corner better.
        val g = phone(128)
        val small = placeInTopCorner(g, true, 60, 60, 30f, margin)
        assertTrue(small.side + 30 < button(g).side + button / 2)
        assertTrue(small.side > button(g).side)
        assertTrue(fits(g, true, small, 30f, margin))
    }

    @Test
    fun `a cutout in the corner drops the button below it, where the corner no longer matters`() {
        val g = phone(128, listOf(CutoutRect(900, 0, 1000, 80)))
        assertEquals(CornerOffset(margin, 80 + margin), button(g))
    }

    @Test
    fun `a full-width cutout strip (no platform cutout rects) drops it below the strip`() {
        val g = phone(null, listOf(CutoutRect(0, 0, width, 121)))
        assertEquals(CornerOffset(margin, 121 + margin), button(g))
    }

    @Test
    fun `a dropped button just below the corner's centre line slides only as far out as the arc needs`() {
        // Top 60 puts the circle's centre at 112.5, above the arc centre's 128.
        val g = phone(128, listOf(CutoutRect(900, 0, 1000, 39)))
        val o = button(g)
        assertEquals(60, o.top)
        assertEquals(sideAt(g, true, 60, button / 2f, margin), o.side)
        assertTrue(o.side in margin..37)
        assertTrue(fits(g, true, o, button / 2f, margin))
    }

    @Test
    fun `a showing status bar keeps the button below it`() {
        val g = phone(128, listOf(punchHole))
        assertEquals(CornerOffset(margin, 121 + margin), button(g, minTop = 121))
    }

    @Test
    fun `the full-width card clears the punch hole, the button doesn't need to`() {
        val g = phone(128, listOf(punchHole))
        val card = placeInTopCorner(g, true, { side -> width - 2 * side }, button, cardCorner, margin)
        assertEquals(CornerOffset(margin, 121 + margin), card)
        // Without a cutout it stays in the corner, its 16 dp corner inside the screen's.
        val clear = placeInTopCorner(phone(128), true, { side -> width - 2 * side }, button, cardCorner, margin)
        assertTrue(clear.top < 121)
        assertTrue(fits(phone(128), true, clear, cardCorner, margin))
    }

    @Test
    fun `a short chip shares the button's row, a long one drops below the punch hole`() {
        val g = phone(128, listOf(punchHole))
        val b = button(g)
        val (short, shortWidth) = placeChip(g, false, 300, button, b, button, margin)
        assertEquals(CornerOffset(37, 37), short)
        assertEquals(300, shortWidth)
        val (long, longWidth) = placeChip(g, false, 700, button, b, button, margin)
        assertEquals(CornerOffset(margin, 121 + margin), long)
        assertEquals(700, longWidth)
        // Capped at the room left of the button.
        val (_, cappedWidth) = placeChip(phone(128), false, 5000, button, b, button, margin)
        assertEquals(width - 37 - (37 + button + margin), cappedWidth)
    }

    @Test
    fun `the chip never sits above the button`() {
        // The button is pushed down by a corner cutout on its side only.
        val g = phone(128, listOf(CutoutRect(900, 0, 1000, 80)))
        val b = button(g)
        val (chip, _) = placeChip(g, false, 300, button, b, button, margin)
        assertEquals(b.top, chip.top)
    }
}
