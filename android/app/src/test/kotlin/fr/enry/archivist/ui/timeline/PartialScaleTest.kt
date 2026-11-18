package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineHistogram
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Timeline place filter step 6: the partial rail's scale (F2). */
class PartialScaleTest {
    /** 40 loaded photos over five days, newest 2026-07-20. */
    private val loaded =
        TimelineHistogram(
            mapOf("2026-07-20" to 10, "2026-07-15" to 5, "2026-06-01" to 10, "2026-05-10" to 5, "2026-03-03" to 10),
            40,
        )
    private val density = DensityScale(loaded)
    private val fractions = (0..200).map { it / 200f }

    @Test
    fun `inside the loaded part, fractions are DensityScale's, scaled by L over N`() {
        val partial = PartialScale(loaded, total = 160)
        assertEquals(0.25f, partial.loadedFraction, 1e-6f)
        for (f in fractions.filter { it * 0.25f > TOP_OF_RAIL_FRACTION }) {
            assertEquals(density.dayAt(f), partial.dayAt(f * 0.25f), "dayAt($f)")
            assertEquals(density.positionAt(f), partial.positionAt(f * 0.25f), "positionAt($f)")
        }
        for (day in loaded.days.keys.map(LocalDate::parse)) {
            assertEquals(density.fractionOfDay(day) * 0.25f, partial.fractionOfDay(day), 1e-6f)
            assertEquals(density.fractionOf(day, 0.5f) * 0.25f, partial.fractionOf(day, 0.5f), 1e-6f)
            assertEquals(density.dayCount(day), partial.dayCount(day))
        }
    }

    @Test
    fun `beyond the loaded part is the oldest loaded day, flagged`() {
        val partial = PartialScale(loaded, total = 160)
        val oldest = LocalDate.parse("2026-03-03")
        for (f in listOf(0.26f, 0.5f, 1f)) {
            assertTrue(partial.isBeyond(f))
            assertEquals(oldest, partial.dayAt(f))
            val p = partial.positionAt(f)
            assertTrue(p.beyond)
            assertEquals(oldest, p.day)
            assertEquals(1f, p.progress)
            assertEquals(10, p.dayCount)
        }
        assertFalse(partial.isBeyond(0.25f))
        assertFalse(partial.positionAt(0.2f).beyond)
        assertNull(partial.dayAt(0f), "the very top is still the present")
    }

    @Test
    fun `ticks only in the loaded part`() {
        val partial = PartialScale(loaded, total = 160)
        val ticks = partial.ticks()
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it.fraction <= 0.25f }, ticks.toString())
        assertTrue(partial.fineTicks(0.9f).all { it.fraction <= 0.25f })
    }

    @Test
    fun `everything loaded behaves exactly like DensityScale`() {
        for (total in listOf(40, 30)) {
            // N = L, and N stale low (everything loaded, the vocabulary undercounted).
            val partial = PartialScale(loaded, total)
            assertEquals(1f, partial.loadedFraction)
            for (f in fractions) {
                assertFalse(partial.isBeyond(f))
                assertEquals(density.dayAt(f), partial.dayAt(f))
                assertEquals(density.positionAt(f), partial.positionAt(f))
                assertEquals(density.fineTicks(f), partial.fineTicks(f))
            }
            assertEquals(density.ticks(), partial.ticks())
            for (day in loaded.days.keys.map(LocalDate::parse)) assertEquals(density.fractionOfDay(day), partial.fractionOfDay(day))
        }
    }

    @Test
    fun `no rail until a first page is in, or without a filter`() {
        assertNull(filteredRailScale(null))
        assertNull(filteredRailScale(FilteredRail(12, TimelineHistogram(emptyMap(), 0), complete = false)))
        assertNull(filteredRailScale(FilteredRail(0, TimelineHistogram(emptyMap(), 0), complete = true)))
        assertEquals(0.25f, filteredRailScale(FilteredRail(160, loaded, complete = false))!!.loadedFraction, 1e-6f)
    }
}
