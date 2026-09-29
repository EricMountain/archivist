package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Coverage for placing the grid partway through a day, so dragging the rail moves the
 * grid continuously instead of snapping from one day's first photo to the next. */
class GridPositionTest {
    private val jul1 = LocalDate.parse("2026-07-01")
    private val mar10 = LocalDate.parse("2026-03-10")
    private val density = DensityScale(TimelineHistogram(mapOf("2026-07-01" to 30, "2026-03-10" to 70), total = 100))

    @Test
    fun `density positionAt reports how far through the day the fraction is`() {
        assertEquals(RailPosition(jul1, 0.5f, 30), density.positionAt(0.15f).rounded())
        assertEquals(RailPosition(mar10, 0f, 70), density.positionAt(0.3f).rounded())
        assertEquals(RailPosition(mar10, 0.5f, 70), density.positionAt(0.65f).rounded())
        assertEquals(RailPosition(mar10, 1f, 70), density.positionAt(1f).rounded())
    }

    @Test
    fun `positionAt agrees with dayAt everywhere, including the top of the rail`() {
        for (i in 0..100) {
            val f = i / 100f
            assertEquals(density.dayAt(f), density.positionAt(f).day, "at $f")
        }
        assertNull(density.positionAt(0f).day)
    }

    /** Moving the finger by a constant amount moves the selection by a constant number of
     * photos — no step at the boundary between two days. */
    @Test
    fun `density position in photos is continuous across a day boundary`() {
        fun photoAt(f: Float): Float {
            val p = density.positionAt(f)
            val start = if (p.day == jul1) 0 else 30
            return start + p.progress * p.dayCount!!
        }
        assertEquals(29f, photoAt(0.29f), 0.01f)
        assertEquals(30f, photoAt(0.30f), 0.01f)
        assertEquals(31f, photoAt(0.31f), 0.01f)
    }

    @Test
    fun `density fractionOf inverts positionAt`() {
        for (f in listOf(0.05f, 0.15f, 0.29f, 0.31f, 0.65f, 0.99f)) {
            val p = density.positionAt(f)
            assertEquals(f, density.fractionOf(p.day!!, p.progress), 0.0001f)
        }
    }

    @Test
    fun `time scale fractionOf inverts positionAt`() {
        val bounds = TimelineBounds(oldest = Instant.parse("2026-01-01T00:00:00Z"), newest = Instant.parse("2026-01-11T00:00:00Z"))
        val scale = TimeScale(bounds, ZoneId.of("UTC"))
        // Half way is 2026-01-06T00:00 exactly: the very end (progress 0) of the 6th's
        // stretch is its following midnight, so pick a mid-day point instead.
        val p = scale.positionAt(0.45f)
        assertEquals(LocalDate.parse("2026-01-06"), p.day)
        assertEquals(0.5f, p.progress, 0.001f)
        assertNull(p.dayCount)
        assertEquals(0.45f, scale.fractionOf(p.day!!, p.progress), 0.0001f)
    }

    private val list = listOf("2026-07-02", "2026-07-01", "2026-07-01", "2026-07-01", "2026-06-30").map(LocalDate::parse)

    @Test
    fun `dayRange finds a day's run in a newest-first list`() {
        assertEquals(1..3, dayRange(list.size, jul1) { list[it] })
        assertEquals(0..0, dayRange(list.size, LocalDate.parse("2026-07-02")) { list[it] })
        assertEquals(4..4, dayRange(list.size, LocalDate.parse("2026-06-30")) { list[it] })
    }

    @Test
    fun `dayRange is null for a day that isn't loaded`() {
        assertNull(dayRange(list.size, LocalDate.parse("2026-06-15")) { list[it] })
        assertNull(dayRange(list.size, LocalDate.parse("2026-08-01")) { list[it] })
        assertNull(dayRange(0, jul1) { list[it] })
    }

    @Test
    fun `dayTargetIndex prefers the scale's own count over what is loaded`() {
        // 30 photos in the day but only 10 loaded: half way is 15 photos in, clamped
        // to the end of the list until paging catches up.
        assertEquals(15f, dayTargetIndex(0, 10, RailPosition(jul1, 0.5f, 30), itemCount = 100))
        assertEquals(11f, dayTargetIndex(0, 10, RailPosition(jul1, 0.5f, 30), itemCount = 12))
        // No scale count (time scale): spread across what's loaded.
        assertEquals(5f, dayTargetIndex(0, 10, RailPosition(jul1, 0.5f), itemCount = 100))
    }

    @Test
    fun `progressAt inverts dayTargetIndex`() {
        val position = RailPosition(jul1, 0.4f, 30)
        val index = dayTargetIndex(7, 30, position, itemCount = 100)
        assertEquals(0.4f, progressAt(index, 7, 30, 30), 0.0001f)
    }

    // ---- leadingCells: columns follow library rank, not list index ---------------------

    /** Library of 3 + 5 + 6 + 4 photos over four days, newest first. */
    private val libraryDays = listOf("2026-01-26" to 3, "2026-01-25" to 5, "2026-01-24" to 6, "2026-01-20" to 4)
    private val library: List<LocalDate> = libraryDays.flatMap { (d, n) -> List(n) { LocalDate.parse(d) } }
    private val ranks = HistogramRanks(TimelineHistogram(libraryDays.toMap(), total = library.size))

    /** The column each photo of a list starting at library rank [from] is drawn in. */
    private fun columnsFrom(
        from: Int,
        columns: Int = 4,
    ): Map<Int, Int> {
        val window = library.drop(from)
        val lead = leadingCells(window.size, columns, ranks) { window.getOrNull(it) }
        return window.indices.associate { i -> (from + i) to (lead + i) % columns }
    }

    /** The regression: a page loaded above shifted every photo sideways, re-wrapping the
     * whole grid, whenever its size wasn't a multiple of the column count. */
    @Test
    fun `loading photos above never moves a photo to another column`() {
        val settled = columnsFrom(8) // starts at 24 Jan's newest photo
        for (from in listOf(0, 1, 3, 6, 7)) { // pages of every alignment prepended
            val grown = columnsFrom(from)
            for ((rank, column) in settled) assertEquals(column, grown[rank], "rank $rank after loading from $from")
        }
    }

    /** Every window with a day boundary in it, or starting at a day's newest photo (14, the
     * last day's start). Past that, a window inside one day can't know its offset in it —
     * see the single-day case below. */
    @Test
    fun `each photo sits in its library rank's column`() {
        for (from in 0..14) {
            for ((rank, column) in columnsFrom(from)) assertEquals(rank % 4, column, "from=$from rank=$rank")
        }
    }

    @Test
    fun `no histogram or a single column needs no lead`() {
        assertEquals(0, leadingCells(5, 4, null) { library[it] })
        assertEquals(0, leadingCells(5, 1, ranks) { library[it] })
        assertEquals(0, leadingCells(0, 4, ranks) { null })
    }

    /** A list that's all one day has no boundary to measure from: it's assumed to start at
     * that day's newest photo. */
    @Test
    fun `a list inside a single day is aligned from that day's start`() {
        val window = List(4) { LocalDate.parse("2026-01-24") }
        assertEquals(8 % 4, leadingCells(window.size, 4, ranks) { window[it] })
    }

    private fun RailPosition.rounded() = copy(progress = Math.round(progress * 1000) / 1000f)
}
