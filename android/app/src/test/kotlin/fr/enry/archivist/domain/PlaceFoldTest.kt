package fr.enry.archivist.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceFoldTest {
    @Test
    fun `strips diacritics`() {
        assertEquals("quebec", PlaceFold.fold("Québec"))
        assertEquals("reykjavik", PlaceFold.fold("Reykjavík"))
        assertEquals("paris 04 hotel-de-ville", PlaceFold.fold("Paris 04 Hôtel-de-Ville"))
    }

    @Test
    fun `lowercases in the root locale`() {
        assertEquals("kyoto", PlaceFold.fold("KYOTO"))
        // Not Turkish dotless-i rules, whatever the device locale.
        assertEquals("istanbul", PlaceFold.fold("ISTANBUL"))
    }

    @Test
    fun `collapses and trims whitespace`() {
        assertEquals("sao paulo", PlaceFold.fold("  São   Paulo "))
        assertEquals("sao paulo", PlaceFold.fold("São\tPaulo "))
        assertEquals("", PlaceFold.fold("   "))
    }

    @Test
    fun `compatibility forms fold too`() {
        // NFKD, not NFD: full-width letters and ligatures decompose.
        assertEquals("kyoto", PlaceFold.fold("ＫＹＯＴＯ"))
        assertEquals("file", PlaceFold.fold("ﬁle"))
    }

    @Test
    fun `non-latin scripts pass through`() {
        assertEquals("京都市", PlaceFold.fold("京都市"))
    }
}
