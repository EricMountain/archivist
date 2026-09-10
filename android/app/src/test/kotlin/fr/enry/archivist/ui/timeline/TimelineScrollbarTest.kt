package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Coverage for the fast-scroll interaction math this project has no Compose UI test
 * harness to drive directly (see `android/AGENTS.md`'s "Robolectric has no native
 * JUnit5 support" entry) — everything here is a pure function precisely so it can be
 * tested without one. The gesture wiring itself (`detectFastScrollGesture`,
 * `TimelineScrollbar` composable) is manual-verification-only. */
class TimelineScrollbarTest {
    private val bounds = TimelineBounds(oldest = Instant.parse("2011-03-02T19:44:10.000Z"), newest = Instant.parse("2026-08-02T16:05:33.000Z"))

    @Test
    fun `instantAtFraction 0 is newest, 1 is oldest`() {
        assertEquals(bounds.newest, instantAtFraction(0f, bounds))
        assertEquals(bounds.oldest, instantAtFraction(1f, bounds))
    }

    @Test
    fun `instantAtFraction clamps out-of-range input`() {
        assertEquals(bounds.newest, instantAtFraction(-0.5f, bounds))
        assertEquals(bounds.oldest, instantAtFraction(1.5f, bounds))
    }

    @Test
    fun `instantAtFraction and fractionAtInstant are inverses at the midpoint`() {
        val mid = instantAtFraction(0.5f, bounds)
        assertEquals(0.5f, fractionAtInstant(mid, bounds), 0.001f)
    }

    @Test
    fun `fractionAtInstant clamps an instant outside the bounds`() {
        assertEquals(0f, fractionAtInstant(bounds.newest.plusSeconds(3600), bounds))
        assertEquals(1f, fractionAtInstant(bounds.oldest.minusSeconds(3600), bounds))
    }

    @Test
    fun `applyFastScrollGain moves 1 to 1 with the finger above the velocity threshold`() {
        val result = applyFastScrollGain(currentFraction = 0.5f, deltaPx = 100f, trackHeightPx = 1000f, velocityPxPerSec = FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S)
        assertEquals(0.6f, result, 0.001f)
    }

    @Test
    fun `applyFastScrollGain moves at the fine gain below the velocity threshold`() {
        val result = applyFastScrollGain(currentFraction = 0.5f, deltaPx = 100f, trackHeightPx = 1000f, velocityPxPerSec = 10f)
        assertEquals(0.5f + 0.1f * FAST_SCROLL_FINE_GAIN, result, 0.001f)
    }

    @Test
    fun `applyFastScrollGain clamps to the track's own ends`() {
        assertEquals(1f, applyFastScrollGain(0.95f, deltaPx = 1000f, trackHeightPx = 1000f, velocityPxPerSec = FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S))
        assertEquals(0f, applyFastScrollGain(0.05f, deltaPx = -1000f, trackHeightPx = 1000f, velocityPxPerSec = FAST_SCROLL_VELOCITY_THRESHOLD_PX_PER_S))
    }

    @Test
    fun `applyFastScrollGain is a no-op with an unmeasured track`() {
        assertEquals(0.5f, applyFastScrollGain(0.5f, deltaPx = 500f, trackHeightPx = 0f, velocityPxPerSec = 5000f))
    }

    @Test
    fun `logTickOffsetPx is zero at the center and grows monotonically, but sub-linearly, with distance`() {
        assertEquals(0f, logTickOffsetPx(0L, 20f))

        val oneDay = logTickOffsetPx(86_400L, 20f)
        val oneWeek = logTickOffsetPx(7 * 86_400L, 20f)
        val oneYear = logTickOffsetPx(365 * 86_400L, 20f)
        assertTrue(oneDay in 0f..oneWeek)
        assertTrue(oneWeek < oneYear)
        // Sub-linear: a year is 365x a day, but its screen offset is nowhere near 365x.
        assertTrue(oneYear < oneDay * 365)
    }

    @Test
    fun `logTickOffsetPx is antisymmetric`() {
        assertEquals(-logTickOffsetPx(86_400L, 20f), logTickOffsetPx(-86_400L, 20f), 0.001f)
    }
}
