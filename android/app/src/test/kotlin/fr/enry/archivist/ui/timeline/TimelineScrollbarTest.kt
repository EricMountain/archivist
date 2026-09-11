package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Coverage for the fast-scroll mapping and rail layout. Everything here is a pure
 * function precisely so it can be tested without a Compose harness (this project has
 * none — see `android/AGENTS.md`'s "Robolectric has no native JUnit5 support" entry);
 * the gesture detection itself is manual-verification-only. */
class TimelineScrollbarTest {
    private val utc = ZoneId.of("UTC")
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

    /** The whole point of the rewrite: the touch position *is* the selection, with no
     * accumulation and no gain, so half way down the track is half way through time. */
    @Test
    fun `fractionAt maps the touch position directly onto the track`() {
        assertEquals(0f, fractionAt(0f, 1000f))
        assertEquals(0.5f, fractionAt(500f, 1000f), 0.0001f)
        assertEquals(1f, fractionAt(1000f, 1000f))
    }

    @Test
    fun `fractionAt clamps past either end and tolerates an unmeasured track`() {
        assertEquals(1f, fractionAt(1500f, 1000f))
        assertEquals(0f, fractionAt(-200f, 1000f))
        assertEquals(0f, fractionAt(500f, 0f))
    }

    /** Releasing at the very top means "back to the present" — an unbounded refresh —
     * rather than a window bounded at `newest`, which the server's exclusive `to` bound
     * would drop the newest photo from, and which would leave no way out of a jump. */
    @Test
    fun `jumpDayFor returns null at the top of the rail and a day elsewhere`() {
        assertNull(jumpDayFor(0f, bounds))
        assertNull(jumpDayFor(0.005f, bounds))
        assertEquals(instantAtFraction(0.5f, bounds).atZone(utc).toLocalDate(), jumpDayFor(0.5f, bounds, utc))
    }

    /** The rail is labelled in months and the pill names a day, so a day is the finest
     * thing a drag can aim at — and picking a *day* rather than an instant is what lets
     * the landing be chosen by the same rule the grid's headers group by. */
    @Test
    fun `jumpDayFor is the day the pill names, in the viewer's own zone`() {
        // Midpoint is deliberately just after UTC midnight, so a zone far enough west is
        // still on the previous day there — the pill and this must agree, whichever it is.
        val justAfterMidnight =
            TimelineBounds(oldest = Instant.parse("2026-01-01T01:00:00.000Z"), newest = Instant.parse("2026-01-03T01:00:00.000Z"))

        assertEquals(LocalDate.parse("2026-01-02"), jumpDayFor(0.5f, justAfterMidnight, utc))
        assertEquals(LocalDate.parse("2026-01-01"), jumpDayFor(0.5f, justAfterMidnight, ZoneId.of("Etc/GMT+12")))
    }

    /**
     * The bottom of the rail used to strand the app. `fraction = 1f` maps to exactly
     * [TimelineBounds.oldest], and the server's `to` bound is *exclusive* of photos at
     * that instant (`timelineSk` is `<takenAt>#<photoId>`, which sorts after a bare
     * timestamp), so the whole-library jump matched nothing, cleared the cache and left
     * the grid on "No photos yet" with no cursor in either direction to recover from.
     * A whole day always contains its own photos.
     */
    @Test
    fun `jumping to the very bottom of the rail selects the oldest photo's own day`() {
        assertEquals(bounds.oldest.atZone(utc).toLocalDate(), jumpDayFor(1f, bounds, utc))
    }

    @Test
    fun `timelineTicks labels a multi-year library by year, in newest-first order`() {
        val ticks = timelineTicks(bounds, zone = utc)

        assertTrue(ticks.size in 2..14, "expected a readable number of ticks, got ${ticks.size}")
        assertTrue(ticks.all { it.label.matches(Regex("\\d{4}")) }, "expected year labels, got ${ticks.map { it.label }}")
        // Fractions increase as the labels go back in time — the rail reads top-down,
        // newest first, exactly like the grid it sits beside.
        assertEquals(ticks.sortedBy { it.fraction }, ticks)
        assertEquals(ticks.map { it.label }.sortedDescending(), ticks.map { it.label })
    }

    @Test
    fun `timelineTicks labels a short library by month`() {
        val shortSpan =
            TimelineBounds(
                oldest = Instant.parse("2026-01-15T00:00:00.000Z"),
                newest = Instant.parse("2026-08-02T00:00:00.000Z"),
            )

        val ticks = timelineTicks(shortSpan, zone = utc)

        assertTrue(ticks.size >= 2, "expected several month ticks, got ${ticks.size}")
        assertTrue(ticks.all { it.label.contains("2026") }, "expected 'MMM yyyy' labels, got ${ticks.map { it.label }}")
    }

    /** Every tick has to sit where a drag to that spot would actually land, or the rail
     * is actively misleading — it's a scale, not decoration. Tolerance is a day because
     * the fraction is a `Float`: across a 15-year library one ULP is about half a minute,
     * which is irrelevant for navigating photos but not exactly a year boundary. */
    @Test
    fun `every tick's fraction round-trips back to its own label's date`() {
        for (tick in timelineTicks(bounds, zone = utc)) {
            val landed = instantAtFraction(tick.fraction, bounds)
            val labelled = Instant.parse("${tick.label}-01-01T00:00:00.000Z")
            val offBy = Duration.between(labelled, landed).abs()
            assertTrue(offBy < Duration.ofDays(1), "tick ${tick.label} lands at $landed, off by $offBy")
        }
    }

    @Test
    fun `timelineTicks still labels both ends of a library spanning less than one step`() {
        val tiny =
            TimelineBounds(
                oldest = Instant.parse("2026-08-01T00:00:00.000Z"),
                newest = Instant.parse("2026-08-03T00:00:00.000Z"),
            )

        val ticks = timelineTicks(tiny, zone = utc)

        assertEquals(2, ticks.size)
        assertEquals(0f, ticks.first().fraction)
        assertEquals(1f, ticks.last().fraction)
    }

    @Test
    fun `timelineTicks is empty for a degenerate range rather than looping`() {
        val instant = Instant.parse("2026-08-02T16:05:33.000Z")
        assertTrue(timelineTicks(TimelineBounds(instant, instant), zone = utc).isEmpty())
    }
}
