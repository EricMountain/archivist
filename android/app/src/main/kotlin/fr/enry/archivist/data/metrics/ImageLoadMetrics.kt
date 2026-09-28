package fr.enry.archivist.data.metrics

import javax.inject.Inject
import javax.inject.Singleton

/** Which kind of image a load was for — grid thumbnails and the detail screen's larger
 * rung share one disk cache but behave very differently, so they're counted apart. */
enum class ImageKind { GRID, DETAIL }

/** How a load ended. The first three are successes, by where the bytes came from. */
enum class LoadOutcome { MEMORY, DISK, NETWORK, CANCELLED, ERROR }

/** Nearest-rank percentiles of a load's time-to-image, in milliseconds. */
data class Percentiles(
    val count: Int,
    val p50: Long,
    val p95: Long,
    val p99: Long,
)

data class KindStats(
    val counts: Map<LoadOutcome, Long>,
    /** Over successful loads of any source, then per source. Null with no samples. */
    val overall: Percentiles?,
    val bySource: Map<LoadOutcome, Percentiles>,
) {
    val successes: Long get() = (counts[LoadOutcome.MEMORY] ?: 0) + (counts[LoadOutcome.DISK] ?: 0) + (counts[LoadOutcome.NETWORK] ?: 0)

    /** Share of successful loads that needed neither the network nor a decrypt. */
    val hitRate: Double? get() = if (successes == 0L) null else ((counts[LoadOutcome.MEMORY] ?: 0) + (counts[LoadOutcome.DISK] ?: 0)).toDouble() / successes
}

data class MetricsSnapshot(
    val sinceMillis: Long,
    val byKind: Map<ImageKind, KindStats>,
)

/**
 * On-device only, in-memory image-load metrics for Settings > Stats: how often a
 * thumbnail came from the memory cache, the disk cache or the network, and how long it
 * took from Coil starting the request to having a decoded image ("time to image" — the
 * frame it's drawn on follows immediately).
 *
 * Nothing here is persisted or sent anywhere: it exists to answer "is the timeline slow
 * because thumbnails are being re-fetched?" on the device in hand, and resets with the
 * process (or the page's own Reset). Counts are cumulative since then; percentiles are
 * over the most recent [window] successful loads per kind, so they describe current
 * behaviour rather than being dominated by a cold start long ago.
 */
@Singleton
class ImageLoadMetrics(
    private val window: Int,
    private val clock: () -> Long,
) {
    @Inject constructor() : this(DEFAULT_WINDOW, System::currentTimeMillis)

    private class Sample(val outcome: LoadOutcome, val millis: Long)

    private class KindState(window: Int) {
        val counts = LongArray(LoadOutcome.entries.size)
        val recent = arrayOfNulls<Sample>(window)
        var next = 0
        var filled = 0
    }

    private val lock = Any()
    private var since = clock()
    private var states = ImageKind.entries.associateWith { KindState(window) }

    fun record(
        kind: ImageKind,
        outcome: LoadOutcome,
        millis: Long,
    ) {
        synchronized(lock) {
            val state = states.getValue(kind)
            state.counts[outcome.ordinal]++
            if (outcome == LoadOutcome.CANCELLED || outcome == LoadOutcome.ERROR) return
            state.recent[state.next] = Sample(outcome, millis)
            state.next = (state.next + 1) % state.recent.size
            if (state.filled < state.recent.size) state.filled++
        }
    }

    fun reset() {
        synchronized(lock) {
            since = clock()
            states = ImageKind.entries.associateWith { KindState(window) }
        }
    }

    fun snapshot(): MetricsSnapshot =
        synchronized(lock) {
            MetricsSnapshot(
                sinceMillis = since,
                byKind =
                    states.mapValues { (_, state) ->
                        val samples = state.recent.take(state.filled).filterNotNull()
                        KindStats(
                            counts = LoadOutcome.entries.associateWith { state.counts[it.ordinal] },
                            overall = percentiles(samples.map { it.millis }),
                            bySource =
                                samples.groupBy { it.outcome }
                                    .mapNotNull { (outcome, group) -> percentiles(group.map { it.millis })?.let { outcome to it } }
                                    .toMap(),
                        )
                    },
            )
        }

    companion object {
        const val DEFAULT_WINDOW = 2000
    }
}

/** Nearest-rank percentiles; null for no samples. */
internal fun percentiles(values: List<Long>): Percentiles? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    fun rank(p: Double) = sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return Percentiles(sorted.size, rank(0.50), rank(0.95), rank(0.99))
}
