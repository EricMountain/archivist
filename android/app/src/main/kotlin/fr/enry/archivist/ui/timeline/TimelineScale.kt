package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.repo.TimelineBounds
import fr.enry.archivist.data.repo.TimelineHistogram
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.pow

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

    /** [dayAt], plus how far through that day [fraction] is — what lets the grid follow
     * the finger continuously rather than jumping from one day's first photo to the
     * next. Its `day` always agrees with [dayAt]. */
    fun positionAt(fraction: Float): RailPosition

    /** Where [day] sits on the track: the inverse of [dayAt], for the idle thumb. */
    fun fractionOfDay(day: LocalDate): Float

    /** How many photos [day] has, if this scale knows — the [RailPosition.dayCount]
     * [positionAt] would report for it. */
    fun dayCount(day: LocalDate): Int?

    /** The inverse of [positionAt]: where [progress] of the way through [day] sits. */
    fun fractionOf(
        day: LocalDate,
        progress: Float,
    ): Float

    /** The labelled points drawn down the rail, top-down (newest first). */
    fun ticks(maxTicks: Int = MAX_TICKS): List<TimelineTick>

    /**
     * The individual days immediately around [centerFraction], for the magnifier lens
     * (`lensWarp`/`lensUnwarp`'s own doc) to actually have something day-grained to show
     * once it's spread that region of track apart. [ticks] alone can't: it's a sparse,
     * whole-library set of ~14 month/year labels, and the whole point of the lens is
     * precision finer than a month. Up to [maxCount] entries, but never assume exactly
     * that many — running off the start or end of the library returns fewer.
     */
    fun fineTicks(
        centerFraction: Float,
        maxCount: Int = FINE_TICK_COUNT,
    ): List<TimelineTick>
}

/** How many day-level ticks the lens shows on each side of the anchor at most — enough
 * to fill the expanded region without crowding into unreadable text. */
internal const val FINE_TICK_COUNT = 9

/**
 * A point on the rail at finer than day grain: [progress] is how far through [day]'s own
 * stretch of track it is, 0 at the day's newest photo (its top on the newest-first rail)
 * towards 1 at its oldest. [day] null is "back to the present", where progress means
 * nothing. [dayCount] is how many photos [day] has, when the scale knows (the histogram
 * does; the time-linear fallback doesn't) — so the grid can turn [progress] into a photo
 * offset without waiting for the whole day to have loaded.
 */
data class RailPosition(
    val day: LocalDate?,
    val progress: Float = 0f,
    val dayCount: Int? = null,
)

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

    override fun positionAt(fraction: Float): RailPosition {
        if (fraction <= TOP_OF_RAIL_FRACTION || days.isEmpty()) return RailPosition(null)
        // Unlike dayAt, the photo position is kept fractional: the part after the
        // decimal point is what moves the grid between one photo and the next.
        val photo = fraction.coerceIn(0f, 1f) * total
        val i = dayIndexOf(photo.toInt().coerceAtMost(total - 1))
        val count = startIndex[i + 1] - startIndex[i]
        val progress = if (count == 0) 0f else ((photo - startIndex[i]) / count).coerceIn(0f, 1f)
        return RailPosition(days[i], progress, count)
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

    override fun dayCount(day: LocalDate): Int? {
        val i = days.binarySearch { other -> day.compareTo(other) }
        return if (i < 0) null else startIndex[i + 1] - startIndex[i]
    }

    override fun fractionOf(
        day: LocalDate,
        progress: Float,
    ): Float {
        if (days.isEmpty() || total == 0) return 0f
        val i = days.binarySearch { other -> day.compareTo(other) }
        if (i < 0) return fractionOfDay(day)
        val count = startIndex[i + 1] - startIndex[i]
        return ((startIndex[i] + progress.coerceIn(0f, 1f) * count) / total).coerceIn(0f, 1f)
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

    /** [days] is exactly the index this needs — newest-first, one entry per day that
     * actually has a photo, which is precisely what makes a day worth offering as a fine
     * tick at all: every one of these is reachable, matching [DensityScale]'s own reason
     * for existing. */
    override fun fineTicks(
        centerFraction: Float,
        maxCount: Int,
    ): List<TimelineTick> {
        val centerDay = dayAt(centerFraction) ?: return emptyList()
        val centerIndex = days.indexOf(centerDay).takeIf { it >= 0 } ?: return emptyList()
        val half = maxCount / 2
        val from = (centerIndex - half).coerceAtLeast(0)
        val to = (centerIndex + half).coerceAtMost(days.lastIndex)
        return (from..to).map { i ->
            val day = days[i]
            TimelineTick(fraction = fractionOfDay(day), label = FINE_TICK_FORMATTER.format(day), major = day == centerDay)
        }
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

    override fun positionAt(fraction: Float): RailPosition {
        if (fraction <= TOP_OF_RAIL_FRACTION) return RailPosition(null)
        val instant = instantAtFraction(fraction, bounds)
        val day = instant.atZone(zone).toLocalDate()
        // Newest-first, so a day's stretch of track starts at its *end* (midnight after).
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val length = end - day.atStartOfDay(zone).toInstant().toEpochMilli()
        val progress = ((end - instant.toEpochMilli()).toFloat() / length).coerceIn(0f, 1f)
        return RailPosition(day, progress)
    }

    override fun fractionOfDay(day: LocalDate): Float =
        fractionAtInstant(day.atStartOfDay(zone).toInstant(), bounds)

    override fun dayCount(day: LocalDate): Int? = null

    override fun fractionOf(
        day: LocalDate,
        progress: Float,
    ): Float {
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val length = end.toEpochMilli() - day.atStartOfDay(zone).toInstant().toEpochMilli()
        return fractionAtInstant(end.minusMillis((progress.coerceIn(0f, 1f) * length).toLong()), bounds)
    }

    override fun ticks(maxTicks: Int): List<TimelineTick> = timelineTicks(bounds, zone, maxTicks)

    /** No histogram to index into here, so the "days" are generated directly: one
     * calendar day per entry, walking outward from the centre. Clamped to [bounds] —
     * this scale doesn't know which of those days have photos (that's exactly what it's
     * standing in for until the histogram arrives), so a day outside the library's own
     * range isn't offered even though the arithmetic would happily produce one. */
    override fun fineTicks(
        centerFraction: Float,
        maxCount: Int,
    ): List<TimelineTick> {
        val centerDay = dayAt(centerFraction) ?: return emptyList()
        val half = maxCount / 2
        return (-half..half).mapNotNull { offset ->
            val day = centerDay.plusDays(offset.toLong())
            val instant = day.atStartOfDay(zone).toInstant()
            if (instant.isBefore(bounds.oldest) || instant.isAfter(bounds.newest)) return@mapNotNull null
            TimelineTick(fraction = fractionAtInstant(instant, bounds), label = FINE_TICK_FORMATTER.format(day), major = offset == 0)
        }
    }
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
internal val FINE_TICK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * The magnifier lens: distorts the track so the region around [center] gets more of the
 * pixels, and everything else correspondingly less. [warp] maps an *underlying* track
 * fraction (where a day naturally sits, per whichever [RailScale] is in use) to where it
 * is *drawn*; [unwarp] is the inverse.
 *
 * [center] (an underlying fraction) is drawn at [at] (a screen fraction). While just
 * peeking the two are equal — the rail magnified in place around the grid's current
 * position. While held, the lens is centred on the selection and drawn at the finger
 * (`Lens(selected, finger)`), which is what keeps the tick under the finger always being
 * the day the pill names, however far the selection and the unmagnified position of the
 * finger have drifted apart over a drag of mixed speeds (see [advanceSelection]).
 *
 * A power law on each side — `at * (u/center)^m` above, its mirror image below — rather
 * than a literal logarithm: bounded to [0,1] with no asymptote to normalise, a closed-form
 * inverse, and the same "steep near the centre, shallow far from it" shape. The exponents
 * are chosen so the zoom right at the centre is [LENS_EXPONENT] on both sides wherever
 * that's possible; when [center] and [at] are far apart one side may have to be zoomed
 * *more* than that just to fit (an exponent below 1 would put the greatest zoom at the
 * edge of the rail instead of the centre), so that side is kept linear.
 */
internal data class Lens(
    val center: Float,
    val at: Float,
    val zoom: Float = LENS_EXPONENT,
) {
    private val c = center.coerceIn(LENS_EDGE_EPSILON, 1f - LENS_EDGE_EPSILON)
    private val p = at.coerceIn(LENS_EDGE_EPSILON, 1f - LENS_EDGE_EPSILON)
    private val above = (zoom * c / p).coerceAtLeast(1f)
    private val below = (zoom * (1f - c) / (1f - p)).coerceAtLeast(1f)

    fun warp(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return if (x <= c) {
            p * (x / c).pow(above)
        } else {
            val t = (x - c) / (1f - c)
            p + (1f - p) * (1f - (1f - t).pow(below))
        }
    }

    fun unwarp(y: Float): Float {
        val x = y.coerceIn(0f, 1f)
        return if (x <= p) {
            c * (x / p).pow(1f / above)
        } else {
            val t = 1f - (1f - (x - p) / (1f - p)).pow(1f / below)
            c + (1f - c) * t
        }
    }
}

/** A lens magnified in place around [anchor] — `Lens(anchor, anchor)`. */
internal fun lensWarp(
    u: Float,
    anchor: Float,
    exponent: Float = LENS_EXPONENT,
): Float = Lens(anchor, anchor, exponent).warp(u)

/** The inverse of [lensWarp]. */
internal fun lensUnwarp(
    p: Float,
    anchor: Float,
    exponent: Float = LENS_EXPONENT,
): Float = Lens(anchor, anchor, exponent).unwarp(p)

/** Keeps the lens centre strictly inside (0,1): at exactly 0 or 1 one side of the warp
 * divides by a zero-width span. */
private const val LENS_EDGE_EPSILON = 0.0001f

private const val LENS_EXPONENT = 6f

/**
 * Where the selection goes when the finger moves from [fromRaw] to [toRaw] (both
 * unmagnified track fractions), at gain [gain].
 *
 * The selection only ever changes here, i.e. only when the finger actually moves. What
 * this replaced — a lens anchor chasing the finger over *time*, with the selection read
 * off through it — kept moving the selection for a few hundred milliseconds after the
 * finger stopped, as the anchor caught up; the grid scrolled on by itself.
 *
 * "Proportional remainder": moving up covers the same *fraction* of the way to the top
 * as the finger does, raised to [gain]; moving down, the same of the way to the bottom.
 * With `gain` 1 and a selection that equals the finger, that's plain direct mapping; with
 * `gain` below 1 the selection moves more finely than the finger (at `gain` `1/k` it's
 * the lens's zoom `k`). Whatever the gain and however far the selection has drifted from
 * the finger, the finger reaching the top of the track selects the very top and the
 * bottom the very bottom — so no drag can strand the selection short of either end.
 */
internal fun advanceSelection(
    selected: Float,
    fromRaw: Float,
    toRaw: Float,
    gain: Float,
): Float =
    when {
        toRaw < fromRaw -> selected * (toRaw / fromRaw).pow(gain)
        toRaw > fromRaw -> 1f - (1f - selected) * ((1f - toRaw) / (1f - fromRaw)).pow(gain)
        else -> selected
    }.coerceIn(0f, 1f)

/**
 * The [advanceSelection] gain for a finger moving at [speedDpPerMs]: the lens's own fine
 * rate (`1/zoom`) at or below [SLOW_DRAG_DP_PER_MS], direct (1) at or above
 * [FAST_DRAG_DP_PER_MS], smoothly in between. Slow is for reading the day ticks one by
 * one; fast is for crossing the library.
 */
internal fun dragGain(
    speedDpPerMs: Float,
    zoom: Float = LENS_EXPONENT,
): Float {
    val t = ((speedDpPerMs - SLOW_DRAG_DP_PER_MS) / (FAST_DRAG_DP_PER_MS - SLOW_DRAG_DP_PER_MS)).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return 1f / zoom + (1f - 1f / zoom) * smooth
}

internal const val SLOW_DRAG_DP_PER_MS = 0.1f
internal const val FAST_DRAG_DP_PER_MS = 1.2f

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
