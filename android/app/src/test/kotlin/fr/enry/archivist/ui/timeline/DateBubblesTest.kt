package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DateBubblesTest {
    private val h = 24
    private val gap = 4
    private val margin = 8
    private val stickyTop = 8

    private val d1 = LocalDate.of(2024, 3, 1)
    private val d2 = LocalDate.of(2024, 3, 2)
    private val d3 = LocalDate.of(2024, 3, 3)

    private fun row(
        row: Int,
        topPx: Int,
        vararg dates: LocalDate?,
    ) = RowGeom(row, topPx, dates.toList())

    private fun place(
        rows: List<RowGeom>,
        above: List<LocalDate?>? = null,
    ) = placeBubbles(rows, above, stickyTop, h, gap, margin)

    private fun photo(
        takenAt: String,
        tzOffsetMin: Int,
    ) = PhotoEntity(
        photoId = takenAt,
        takenAt = takenAt,
        tzOffsetMin = tzOffsetMin,
        mime = "image/jpeg",
        width = 1,
        height = 1,
        status = AssetStatus.READY,
        thumbs = emptyMap(),
        encDek = "d",
        encKeyId = "k",
    )

    @Test
    fun `row 0 is always labelled`() {
        assertEquals(listOf(Bubble(d1, 8, 0)), place(listOf(row(0, 0, d1, d1))))
    }

    @Test
    fun `row date is the max local date, not the first cell's`() {
        val a = photo("2024-03-03T12:00:00.000Z", 0).localDate()
        // 2024-03-04T20:00Z at +14:00 is 5 Mar local.
        val b = photo("2024-03-04T20:00:00.000Z", 840).localDate()
        assertEquals(LocalDate.of(2024, 3, 5), b)
        assertEquals(b, place(listOf(row(0, 0, a, b, a))).single().date)
    }

    @Test
    fun `same date as the row above gets no bubble, a different date does`() {
        val out = place(listOf(row(0, 0, d1), row(1, 100, d1), row(2, 200, d2)))
        assertEquals(listOf(Bubble(d1, 8, 0), Bubble(d2, 208, 2)), out)
    }

    @Test
    fun `sticky bubble pins to the top when the top row is scrolled off`() {
        val out = place(listOf(row(3, -50, d1), row(4, 50, d1)), above = listOf(d1))
        assertEquals(listOf(Bubble(d1, stickyTop, 3)), out)
    }

    @Test
    fun `sticky bubble is not pushed while the next label is far enough away`() {
        // next label at 40 + 8 = 48; limit = 48 - 24 - 4 = 20, above the pinned y of 8.
        val out = place(listOf(row(3, -50, d1), row(4, 40, d2)), above = listOf(d1))
        assertEquals(listOf(Bubble(d1, 8, 3), Bubble(d2, 48, 4)), out)
    }

    @Test
    fun `sticky bubble is pushed by exactly the overlap`() {
        val out = place(listOf(row(3, -50, d1), row(4, 20, d2)), above = listOf(d1))
        // unconstrained y = 8; limit = 28 - 28 = 0 -> pushed up by 8, exactly the overlap.
        assertEquals(0, out.first().yPx)
    }

    @Test
    fun `incoming bubble pins at the sticky line and the old one slides off above it`() {
        // Row 4's natural y is 6: past the pin line (8), so it owns the pin; the d1
        // bubble it replaced sits bubble+gap above its natural position.
        val out = place(listOf(row(3, -100, d1), row(4, -2, d2)), above = listOf(d1))
        assertEquals(setOf(Bubble(d2, 8, 4), Bubble(d1, 6 - h - gap, 3)), out.toSet())
    }

    @Test
    fun `pushed-off bubble is dropped once wholly above the screen`() {
        val out = place(listOf(row(3, -100, d1), row(4, -12, d2)), above = listOf(d1))
        assertEquals(listOf(Bubble(d2, 8, 4)), out)
    }

    @Test
    fun `handover is continuous both ways, and the incoming bubble never overshoots the pin`() {
        var prev: Map<LocalDate, Int>? = null
        // Row 4 moves from well below the pin line to well above it, one pixel at a time.
        for (top in 60 downTo -60) {
            val out = place(listOf(row(3, top - 100, d1), row(4, top, d2), row(5, top + 100, d2)), above = listOf(d1))
            val y = out.associate { it.date to it.yPx }
            assertTrue(y.getValue(d2) >= stickyTop, "d2 at $top overshot the pin: ${y[d2]}")
            prev?.let { p ->
                for (date in y.keys intersect p.keys) {
                    assertTrue(Math.abs(y.getValue(date) - p.getValue(date)) <= 1, "$date jumped at $top: ${p[date]} -> ${y[date]}")
                }
            }
            prev = y
        }
    }

    @Test
    fun `unlabelled top row still shows its date`() {
        val out = place(listOf(row(5, 10, d2)), above = listOf(d2))
        assertEquals(listOf(Bubble(d2, 18, 5)), out)
    }

    @Test
    fun `a placeholder row gets no bubble and does not make the next row different`() {
        val out = place(listOf(row(0, 0, d1), row(1, 100, d1, null), row(2, 200, d1)))
        assertEquals(listOf(Bubble(d1, 8, 0)), out)
    }

    @Test
    fun `a repeated date across non-adjacent rows labels all three`() {
        val out = place(listOf(row(0, 0, d1), row(1, 100, d2), row(2, 200, d1)))
        assertEquals(listOf(d1, d2, d1), out.map { it.date })
        assertTrue(out.map { it.row }.toSet().size == 3)
    }
}
