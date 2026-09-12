package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
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
    fun `either scale returns null at the top of the rail and a day elsewhere`() {
        val time = TimeScale(bounds, utc)
        assertNull(time.dayAt(0f))
        assertNull(time.dayAt(0.005f))
        assertEquals(instantAtFraction(0.5f, bounds).atZone(utc).toLocalDate(), time.dayAt(0.5f))

        val density = DensityScale(TimelineHistogram(mapOf("2026-01-01" to 1), total = 1))
        assertNull(density.dayAt(0.005f))
    }

    /** The fallback scale names the day in the viewer's own zone — the same day the pill
     * shows. Midpoint deliberately just after UTC midnight, so a zone far enough west is
     * still on the previous day. */
    @Test
    fun `the time scale's day is the viewer's own`() {
        val justAfterMidnight =
            TimelineBounds(oldest = Instant.parse("2026-01-01T01:00:00.000Z"), newest = Instant.parse("2026-01-03T01:00:00.000Z"))

        assertEquals(LocalDate.parse("2026-01-02"), TimeScale(justAfterMidnight, utc).dayAt(0.5f))
        assertEquals(LocalDate.parse("2026-01-01"), TimeScale(justAfterMidnight, ZoneId.of("Etc/GMT+12")).dayAt(0.5f))
    }

    /**
     * The bottom of the rail used to strand the app. `fraction = 1f` maps to exactly
     * [TimelineBounds.oldest], and the server's `to` bound is *exclusive* of photos at
     * that instant, so the whole-library jump matched nothing, cleared the cache and left
     * the grid on "No photos yet". A whole day always contains its own photos.
     */
    @Test
    fun `the bottom of the rail selects the oldest photo's own day`() {
        assertEquals(bounds.oldest.atZone(utc).toLocalDate(), TimeScale(bounds, utc).dayAt(1f))
    }

    // ---- DensityScale: position is cumulative photo count, not elapsed time --------

    /** A holiday day with 90 photos and a quiet one with 10. By elapsed time their
     * shares of the track would depend on how far apart they are; by count the holiday
     * gets 90% — which is the point, since that is the part worth navigating. */
    @Test
    fun `density scale gives a dense period most of the track`() {
        val scale = DensityScale(TimelineHistogram(mapOf("2026-07-01" to 90, "2025-11-15" to 10), total = 100))

        // The top 90% of the track is all the holiday day; only the bottom 10% is the
        // quiet one. A time scale would give the holiday a sliver.
        assertEquals(LocalDate.parse("2026-07-01"), scale.dayAt(0.5f))
        assertEquals(LocalDate.parse("2026-07-01"), scale.dayAt(0.89f))
        assertEquals(LocalDate.parse("2025-11-15"), scale.dayAt(0.95f))
    }

    /** It only ever names days that are in the histogram — so the pill can't offer a
     * date that has no photos, which is what "land on the date I picked" needs. */
    @Test
    fun `density scale only names days that have photos`() {
        val days = mapOf("2026-07-01" to 3, "2026-03-10" to 5, "2025-12-25" to 2)
        val scale = DensityScale(TimelineHistogram(days, total = 10))

        val named = (2..100).map { scale.dayAt(it / 100f) }.toSet()
        assertEquals(days.keys.map(LocalDate::parse).toSet(), named)
    }

    @Test
    fun `density scale runs newest at the top to oldest at the bottom`() {
        val scale = DensityScale(TimelineHistogram(mapOf("2026-07-01" to 1, "2026-03-10" to 1, "2025-12-25" to 1), total = 3))

        assertEquals(LocalDate.parse("2026-07-01"), scale.dayAt(0.1f))
        assertEquals(LocalDate.parse("2025-12-25"), scale.dayAt(1f))
    }

    /** The idle thumb reads the scale backwards; the two have to agree or the thumb would
     * sit somewhere a drag to it wouldn't land. */
    @Test
    fun `density fractionOfDay is the start of that day's share of the track`() {
        val scale = DensityScale(TimelineHistogram(mapOf("2026-07-01" to 30, "2026-03-10" to 70), total = 100))

        assertEquals(0f, scale.fractionOfDay(LocalDate.parse("2026-07-01")), 0.0001f)
        assertEquals(0.3f, scale.fractionOfDay(LocalDate.parse("2026-03-10")), 0.0001f)
        // Round trip: the day at the thumb's position is the day the thumb was placed for.
        assertEquals(LocalDate.parse("2026-03-10"), scale.dayAt(scale.fractionOfDay(LocalDate.parse("2026-03-10")) + 0.01f))
    }

    /** Labels are spaced by track and named after whatever month is there, so a dense
     * month spanning several slots is labelled once rather than printed four times. */
    @Test
    fun `density ticks never repeat a label consecutively`() {
        val scale = DensityScale(TimelineHistogram(mapOf("2026-07-01" to 95, "2025-11-15" to 5), total = 100))

        val labels = scale.ticks().map { it.label }
        assertEquals(labels.zipWithNext().none { (a, b) -> a == b }, true)
        assertEquals(listOf("Jul 2026", "Nov 2025"), labels)
    }

    @Test
    fun `railScale prefers density, falls back to time, and is null with neither`() {
        val histogram = TimelineHistogram(mapOf("2026-07-01" to 1), total = 1)
        assertTrue(railScale(histogram, bounds, utc) is DensityScale)
        assertTrue(railScale(null, bounds, utc) is TimeScale)
        // An empty histogram (a library with nothing live) says nothing about density.
        assertTrue(railScale(TimelineHistogram(emptyMap(), 0), bounds, utc) is TimeScale)
        assertNull(railScale(null, null, utc))
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
