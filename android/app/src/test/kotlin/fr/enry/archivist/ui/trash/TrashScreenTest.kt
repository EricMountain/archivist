package fr.enry.archivist.ui.trash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Plan step 2.13's pure formatting helpers, `internal` for the same reason
 * [fr.enry.archivist.ui.detail.DetailFormattingTest]'s are — testable with no Compose
 * involved. */
class TrashScreenTest {
    @Test
    fun `blockedAttemptWarning matches design md's own wording, singular attempt`() {
        assertEquals(
            "1 attempt to re-upload this from home-server — delete it there too, or it returns.",
            blockedAttemptWarning(1, "home-server"),
        )
    }

    @Test
    fun `blockedAttemptWarning pluralises multiple attempts`() {
        assertEquals(
            "3 attempts to re-upload this from home-server — delete it there too, or it returns.",
            blockedAttemptWarning(3, "home-server"),
        )
    }

    @Test
    fun `blockedAttemptWarning falls back to a generic source when lastAttemptBy is absent`() {
        assertEquals(
            "2 attempts to re-upload this from another device — delete it there too, or it returns.",
            blockedAttemptWarning(2, null),
        )
    }

    @Test
    fun `formatTrashDate uses the given offset, not UTC`() {
        // 23:30 UTC on the 30th, at +02:00, is 01:30 local on the 31st -- same case
        // DetailFormattingTest's formatDate test exercises for the detail screen.
        assertEquals("31 Aug 2026, 01:30", formatTrashDate("2026-08-30T23:30:00.000Z", tzOffsetMin = 120))
    }
}
