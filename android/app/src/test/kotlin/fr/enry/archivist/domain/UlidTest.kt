package fr.enry.archivist.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UlidTest {
    // Mirrors src/core/ids.ts's ULID_RE exactly -- server-side validation is a regex
    // match, nothing more, so this is what actually has to hold.
    private val ulidRegex = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")

    @Test
    fun `generates a 26-character string matching the server's ULID regex`() {
        val id = Ulid.generate()
        assertEquals(26, id.length)
        assertTrue(ulidRegex.matches(id)) { "'$id' doesn't match the server's ULID regex" }
    }

    @Test
    fun `two calls never collide`() {
        val ids = (1..1000).map { Ulid.generate() }
        assertEquals(1000, ids.toSet().size)
    }

    @Test
    fun `a later timestamp sorts after an earlier one`() {
        val earlier = Ulid.generate(now = 1_000_000L)
        val later = Ulid.generate(now = 2_000_000L)
        assertTrue(earlier < later) { "'$earlier' should sort before '$later'" }
    }

    @Test
    fun `the epoch encodes as all zeros in the time component`() {
        val id = Ulid.generate(now = 0L)
        assertEquals("0000000000", id.take(10))
    }
}
