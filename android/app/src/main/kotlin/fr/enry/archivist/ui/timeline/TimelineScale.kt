package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What the fast-scroll rail maps a finger position onto.
 *
 * Two implementations, and which one is in use is the difference between a scrollbar
 * that is useful on a real library and one that isn't. See [DensityScale] for why.
 */
internal interface RailScale {
    /** The day a release at [fraction] of the track selects, or null for the very top,
     * which means "back to the present" rather than a bounded window — see
     * `TimelineScrollbar.jumpDayFor`. */
    fun dayAt(fraction: Float): LocalDate?

    /** Where [day] sits on the track: the inverse of [dayAt], for the idle thumb. */
    fun fractionOfDay(day: LocalDate): Float

    /** The labelled points drawn down the rail, top-down (newest first). */
    fun ticks(maxTicks: Int = MAX_TICKS): List<TimelineTick>
}

/** One label on the fast-scroll rail. [fraction] is its position along the track. */
internal data class TimelineTick(
    val fraction: Float,
    val label: String,
    val major: Boolean,
)

internal const val MAX_TICKS = 14

/** A release above this is "back to the present" rather than a bounded window. */
internal const val TOP_OF_RAIL_FRACTION = 0.01f

/**
 * Track position is **cumulative photo count**, not elapsed time — design.md pattern 15.
 *
 * Elapsed time is the obvious mapping and the wrong one for navigating a photo library:
 * a fortnight's holiday and the eight quiet months after it get the same share of the
 * track, so the part of the library actually worth reaching is squeezed into a few
 * pixels while empty stretches get most of the rail. Weighting by count gives every
 * photo the same slice, which is what makes a dense period aimable at all.
 *
 * It also disposes of "the date I picked had no photos" outright: this only ever names
 * days that are *in* the histogram, so a day the pill offers is a day that exists.
 *
 * Days are indexed newest-first, matching the grid the rail sits beside — fraction 0 is
 * the newest photo, 1 the oldest.
 */
internal class DensityScale(histogram: TimelineHistogram) : RailScale {
    /** Newest first. */
    private val days: List<LocalDate> = histogram.days.keys.map(LocalDate::parse).sortedDescending()

    /** `startIndex[i]` is how many photos are newer than `days[i]`, so the day covers
     * the track from `startIndex[i] / total` to `startIndex[i + 1] / total`. One extra
     * trailing entry holds the total, which removes the bounds check from every lookup. */
    private val startIndex: IntArray =
        IntArray(days.size + 1).also { acc ->
            var running = 0
            days.forEachIndexed { i, day ->
                acc[i] = running
                running += histogram.days[day.toString()] ?: 0
            }
            acc[days.size] = running
        }

    private val total: Int get() = startIndex.last()

    override fun dayAt(fraction: Float): LocalDate? {
        if (fraction <= TOP_OF_RAIL_FRACTION || days.isEmpty()) return null
        // The photo under the finger, then the day that photo belongs to. Clamped off the
        // end because fraction 1f maps to index `total`, one past the last photo.
        val photoIndex = (fraction.coerceIn(0f, 1f) * total).toInt().coerceAtMost(total - 1)
        return days[dayIndexOf(photoIndex)]
    }

    /** Binary search for the day whose photo range contains [photoIndex]. */
    private fun dayIndexOf(photoIndex: Int): Int {
        var lo = 0
        var hi = days.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (startIndex[mid] <= photoIndex) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun fractionOfDay(day: LocalDate): Float {
        if (days.isEmpty() || total == 0) return 0f
        // Days are descending, so a day newer than everything cached sits at the top and
        // one older than everything sits at the bottom.
        val i = days.binarySearch { other -> day.compareTo(other) }
        val index = if (i >= 0) i else (-i - 1)
        return (startIndex[index.coerceIn(0, days.size)].toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Labels placed at even *track* intervals and named after whatever month is there,
     * rather than at even time intervals.
     *
     * That is the only thing that works once position is density-weighted: a month with
     * no photos occupies no track, so a label placed at its start would sit on top of its
     * neighbour. Spacing by track and reading off the month means labels crowd where the
     * photos are — which is also where a reader needs them.
     */
    override fun ticks(maxTicks: Int): List<TimelineTick> {
        if (days.isEmpty()) return emptyList()
        val out = mutableListOf<TimelineTick>()
        var lastLabel: String? = null
        var lastYear: Int? = null
        for (i in 0 until maxTicks) {
            val fraction = i.toFloat() / (maxTicks - 1)
            val day = dayAt(fraction.coerceAtLeast(TOP_OF_RAIL_FRACTION + 0.001f)) ?: continue
            val label = MONTH_TICK_FORMATTER.format(day)
            // Consecutive repeats are dropped rather than spaced out: a month dense enough
            // to span several ticks says so by the room it takes up, and printing its name
            // four times down the rail reads as a rendering fault.
            if (label == lastLabel) continue
            out += TimelineTick(fraction, label, major = day.year != lastYear)
            lastLabel = label
            lastYear = day.year
        }
        return out
    }
}

/**
 * The fallback while the histogram hasn't loaded — position is linear in time.
 *
 * Kept rather than blocking the rail on the histogram: the scrollbar has to work on the
 * very first launch, offline, and for the moment between opening the app and the
 * revalidation completing. It is worse (see [DensityScale]) but it is not wrong.
 */
internal class TimeScale(private val bounds: TimelineBounds, private val zone: ZoneId) : RailScale {
    override fun dayAt(fraction: Float): LocalDate? {
        if (fraction <= TOP_OF_RAIL_FRACTION) return null
        return instantAtFraction(fraction, bounds).atZone(zone).toLocalDate()
    }

    override fun fractionOfDay(day: LocalDate): Float =
        fractionAtInstant(day.atStartOfDay(zone).toInstant(), bounds)

    override fun ticks(maxTicks: Int): List<TimelineTick> = timelineTicks(bounds, zone, maxTicks)
}

/** Fraction along the track -> the [Instant] it represents, linear in time. `0f` is
 * [TimelineBounds.newest] (top of the newest-first grid), `1f` is
 * [TimelineBounds.oldest]. */
internal fun instantAtFraction(
    fraction: Float,
    bounds: TimelineBounds,
): Instant {
    val clamped = fraction.coerceIn(0f, 1f)
    val totalMillis = bounds.newest.toEpochMilli() - bounds.oldest.toEpochMilli()
    // Double, not Float: a multi-year range in milliseconds (order 1e11) already exceeds
    // a Float's ~7 significant digits, which rounded fraction=1f visibly short of oldest.
    return bounds.newest.minusMillis((totalMillis * clamped.toDouble()).toLong())
}

/** The inverse of [instantAtFraction]. */
internal fun fractionAtInstant(
    instant: Instant,
    bounds: TimelineBounds,
): Float {
    val totalMillis = (bounds.newest.toEpochMilli() - bounds.oldest.toEpochMilli()).coerceAtLeast(1)
    val elapsed = bounds.newest.toEpochMilli() - instant.toEpochMilli()
    return (elapsed.toDouble() / totalMillis).toFloat().coerceIn(0f, 1f)
}

/**
 * [TimeScale]'s labels: the whole library as a handful of round dates — years for a long
 * library, months for a short one. Granularity adapts to the span because both extremes
 * are useless: month labels across fifteen years are an unreadable smear, and year labels
 * across eight months are a single tick.
 *
 * Positions are linear in *time*, matching [instantAtFraction] exactly — a rail whose
 * labels didn't agree with where a drag actually lands would be worse than none.
 */
internal fun timelineTicks(
    bounds: TimelineBounds,
    zone: ZoneId = ZoneId.systemDefault(),
    maxTicks: Int = MAX_TICKS,
): List<TimelineTick> {
    val oldest = bounds.oldest.atZone(zone)
    val newest = bounds.newest.atZone(zone)
    if (!oldest.isBefore(newest)) return emptyList()

    val months = ChronoUnit.MONTHS.between(oldest.withDayOfMonth(1), newest.withDayOfMonth(1)).toInt() + 1
    val stepMonths = TICK_STEPS_MONTHS.firstOrNull { months / it <= maxTicks } ?: TICK_STEPS_MONTHS.last()
    val yearOnly = stepMonths >= 12

    // Labels land on round dates (a January, or a quarter start) rather than wherever
    // the library happens to begin — the point is a legible scale, not an exact
    // reproduction of the range's endpoints.
    val alignTo = if (yearOnly) 12 else stepMonths
    var cursor = oldest.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
    while ((cursor.monthValue - 1) % alignTo != 0) cursor = cursor.plusMonths(1)
    while (cursor.isBefore(oldest)) cursor = cursor.plusMonths(stepMonths.toLong())

    val ticks = mutableListOf<TimelineTick>()
    while (!cursor.isAfter(newest)) {
        ticks +=
            TimelineTick(
                fraction = fractionAtInstant(cursor.toInstant(), bounds),
                label = if (yearOnly) cursor.year.toString() else MONTH_TICK_FORMATTER.format(cursor),
                major = if (yearOnly) cursor.year % 5 == 0 else cursor.monthValue == 1,
            )
        cursor = cursor.plusMonths(stepMonths.toLong())
    }

    // A library spanning less than one step boundary would otherwise draw an empty rail,
    // which reads as broken rather than as "there's not much here".
    if (ticks.size < 2) {
        return listOf(
            TimelineTick(0f, MONTH_TICK_FORMATTER.format(newest), major = true),
            TimelineTick(1f, MONTH_TICK_FORMATTER.format(oldest), major = true),
        )
    }
    // Top-down, i.e. newest first and fraction ascending — the order the rail is read in,
    // and the same order as the grid beside it. Generation walks the other way because
    // it has to start from a round boundary at the old end.
    return ticks.asReversed()
}

/** Month steps to try, coarsening until the whole range fits in `maxTicks` labels. */
private val TICK_STEPS_MONTHS = listOf(1, 2, 3, 6, 12, 24, 60, 120)

internal val MONTH_TICK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * The scale the rail should use: density when the histogram has arrived and has anything
 * in it, elapsed time otherwise.
 *
 * Null when neither is available — a brand-new install that has not reached the server
 * yet — which is what makes `TimelineScrollbar` draw nothing at all rather than a rail
 * that maps positions onto a range it doesn't know.
 */
internal fun railScale(
    histogram: TimelineHistogram?,
    bounds: TimelineBounds?,
    zone: ZoneId = ZoneId.systemDefault(),
): RailScale? =
    when {
        histogram != null && histogram.total > 0 && histogram.days.isNotEmpty() -> DensityScale(histogram)
        bounds != null -> TimeScale(bounds, zone)
        else -> null
    }
