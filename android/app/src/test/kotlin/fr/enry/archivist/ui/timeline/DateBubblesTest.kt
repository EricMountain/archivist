package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
    ) = RowGeom(row, topPx, dates.map { it?.let { d -> BubbleCell(d, "${d}T12:00:00.000Z") } })

    private fun place(
        rows: List<RowGeom>,
        above: List<LocalDate?>? = null,
    ) = placeBubbles(rows, above?.map { it?.let { d -> BubbleCell(d, "${d}T12:00:00.000Z") } }, stickyTop, h, gap, margin)

    /** A located cell: [place] is both key and name, [time] the UTC time of day. */
    private fun cell(
        date: LocalDate,
        place: String?,
        time: String = "12:00:00",
    ) = BubbleCell(date, "${date}T$time.000Z", place, place)

    private fun placedRow(
        row: Int,
        topPx: Int,
        vararg cells: BubbleCell,
    ) = RowGeom(row, topPx, cells.toList())

    private fun placeCells(
        rows: List<RowGeom>,
        above: List<BubbleCell?>? = null,
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

    // --- Places (geolocation plan step 6, D7) ---

    @Test
    fun `same date and a different place labels the row`() {
        val out =
            placeCells(
                listOf(
                    placedRow(0, 0, cell(d1, "Kyoto"), cell(d1, "Kyoto")),
                    placedRow(1, 100, cell(d1, "Kyoto"), cell(d1, "Kyoto")),
                    placedRow(2, 200, cell(d1, "Osaka"), cell(d1, "Osaka")),
                ),
            )
        assertEquals(listOf(Bubble(d1, 8, 0, "Kyoto"), Bubble(d1, 208, 2, "Osaka")), out)
    }

    @Test
    fun `an unknown place never labels a row, nor makes the next one different`() {
        val out =
            placeCells(
                listOf(
                    placedRow(0, 0, cell(d1, "Kyoto")),
                    placedRow(1, 100, cell(d1, null)),
                    placedRow(2, 200, cell(d1, "Kyoto")),
                ),
            )
        assertEquals(listOf(Bubble(d1, 8, 0, "Kyoto")), out)
    }

    @Test
    fun `a known place after an unknown one on the same date labels only if it changed`() {
        val out =
            placeCells(
                listOf(
                    placedRow(0, 0, cell(d1, "Kyoto")),
                    placedRow(1, 100, cell(d1, null)),
                    placedRow(2, 200, cell(d1, "Osaka")),
                ),
            )
        assertEquals(listOf(Bubble(d1, 8, 0, "Kyoto"), Bubble(d1, 208, 2, "Osaka")), out)
    }

    @Test
    fun `a new date with an unknown place is labelled with the date alone`() {
        val out = placeCells(listOf(placedRow(0, 0, cell(d2, "Kyoto")), placedRow(1, 100, cell(d1, null))))
        assertEquals(listOf(Bubble(d2, 8, 0, "Kyoto"), Bubble(d1, 108, 1, null)), out)
    }

    @Test
    fun `the most common place wins`() {
        val label = rowLabel(listOf(cell(d1, "Osaka", "18:00:00"), cell(d1, "Kyoto", "10:00:00"), cell(d1, "Kyoto", "09:00:00"), cell(d1, null)))
        assertEquals(RowLabel(d1, "Kyoto", "Kyoto"), label)
    }

    @Test
    fun `a tie goes to the place of the latest takenAt`() {
        val cells = listOf(cell(d1, "Kyoto", "09:00:00"), cell(d1, "Osaka", "11:00:00"), cell(d1, "Kyoto", "10:00:00"), cell(d1, "Osaka", "08:00:00"))
        assertEquals("Osaka", rowLabel(cells)?.placeKey)
        assertEquals("Osaka", rowLabel(cells.reversed())?.placeKey)
    }

    @Test
    fun `a row mixing dates takes its place only from the row date's photos`() {
        // d2 is the row's date; the two d1 photos in Osaka don't vote.
        val label = rowLabel(listOf(cell(d2, "Kyoto", "01:00:00"), cell(d1, "Osaka", "23:00:00"), cell(d1, "Osaka", "22:00:00")))
        assertEquals(RowLabel(d2, "Kyoto", "Kyoto"), label)
        // And with no located photo on the row's date, the row has no place.
        assertEquals(RowLabel(d2), rowLabel(listOf(cell(d2, null), cell(d1, "Osaka"))))
    }

    @Test
    fun `place keys, not names, tell places apart`() {
        val paris = BubbleCell(d1, "${d1}T12:00:00.000Z", "2988507", "Paris")
        val parisTx = BubbleCell(d1, "${d1}T11:00:00.000Z", "4717560", "Paris")
        val out = placeCells(listOf(placedRow(0, 0, paris), placedRow(1, 100, parisTx)))
        assertEquals(2, out.size)
    }

    @Test
    fun `the sticky bubble shows the place it inherits`() {
        val out = placeCells(listOf(placedRow(3, -50, cell(d1, null))), above = listOf(cell(d1, "Kyoto")))
        assertEquals(listOf(Bubble(d1, stickyTop, 3, "Kyoto")), out)
    }

    @Test
    fun `the row above decides whether the first visible row is different`() {
        val out = placeCells(listOf(placedRow(3, -50, cell(d1, "Kyoto")), placedRow(4, 50, cell(d1, "Osaka"))), above = listOf(cell(d1, "Kyoto")))
        assertEquals(listOf(Bubble(d1, 8, 3, "Kyoto"), Bubble(d1, 58, 4, "Osaka")), out)
    }

    @Test
    fun `bubble text is date then place`() {
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.UK)
        assertEquals("14 Jul 2026" to " · Kyoto", bubbleText(Bubble(LocalDate.of(2026, 7, 14), 0, 0, "Kyoto"), fmt))
        assertEquals("14 Jul 2026" to null, bubbleText(Bubble(LocalDate.of(2026, 7, 14), 0, 0), fmt))
    }
}
