package fr.enry.archivist.data.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImageLoadMetricsTest {
    private var now = 1_000L
    private val metrics = ImageLoadMetrics(window = 4) { now }

    @Test
    fun `percentiles are nearest-rank`() {
        val p = percentiles((1L..100L).toList())!!
        assertEquals(100, p.count)
        assertEquals(50, p.p50)
        assertEquals(95, p.p95)
        assertEquals(99, p.p99)
        assertEquals(Percentiles(1, 7, 7, 7), percentiles(listOf(7L)))
        assertNull(percentiles(emptyList()))
    }

    @Test
    fun `hit rate counts memory and disk against all successes, not cancellations`() {
        metrics.record(ImageKind.GRID, LoadOutcome.MEMORY, 0)
        metrics.record(ImageKind.GRID, LoadOutcome.DISK, 5)
        metrics.record(ImageKind.GRID, LoadOutcome.NETWORK, 200)
        metrics.record(ImageKind.GRID, LoadOutcome.NETWORK, 300)
        metrics.record(ImageKind.GRID, LoadOutcome.CANCELLED, 50)
        metrics.record(ImageKind.GRID, LoadOutcome.ERROR, 50)

        val grid = metrics.snapshot().byKind.getValue(ImageKind.GRID)
        assertEquals(0.5, grid.hitRate!!, 1e-9)
        assertEquals(1L, grid.counts[LoadOutcome.CANCELLED])
        assertEquals(1L, grid.counts[LoadOutcome.ERROR])
        assertEquals(Percentiles(2, 200, 300, 300), grid.bySource[LoadOutcome.NETWORK])
        assertEquals(4, grid.overall!!.count)
    }

    @Test
    fun `kinds are counted separately`() {
        metrics.record(ImageKind.DETAIL, LoadOutcome.NETWORK, 900)
        val snapshot = metrics.snapshot()
        assertNull(snapshot.byKind.getValue(ImageKind.GRID).hitRate)
        assertEquals(0.0, snapshot.byKind.getValue(ImageKind.DETAIL).hitRate!!, 1e-9)
    }

    @Test
    fun `percentiles cover only the most recent window, counts everything`() {
        repeat(4) { metrics.record(ImageKind.GRID, LoadOutcome.NETWORK, 1000) }
        repeat(4) { metrics.record(ImageKind.GRID, LoadOutcome.DISK, 10) }

        val grid = metrics.snapshot().byKind.getValue(ImageKind.GRID)
        assertEquals(4L, grid.counts[LoadOutcome.NETWORK])
        assertEquals(Percentiles(4, 10, 10, 10), grid.overall)
        assertNull(grid.bySource[LoadOutcome.NETWORK])
    }

    @Test
    fun `reset clears everything and restarts the clock`() {
        metrics.record(ImageKind.GRID, LoadOutcome.NETWORK, 100)
        now = 5_000L
        metrics.reset()

        val snapshot = metrics.snapshot()
        assertEquals(5_000L, snapshot.sinceMillis)
        assertEquals(0L, snapshot.byKind.getValue(ImageKind.GRID).counts[LoadOutcome.NETWORK])
        assertNull(snapshot.byKind.getValue(ImageKind.GRID).overall)
    }
}
