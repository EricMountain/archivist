package fr.enry.archivist.ui.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewPlaybackTest {
    @Test
    fun `no fade for most of the clip`() {
        assertEquals(0f, fadeOutAlpha(positionMs = 0, durationMs = 10_000))
        assertEquals(0f, fadeOutAlpha(positionMs = 5_000, durationMs = 10_000))
        assertEquals(0f, fadeOutAlpha(positionMs = 9_500, durationMs = 10_000)) // exactly where the fade starts
    }

    @Test
    fun `fade ramps linearly over the last half second to fully black`() {
        assertEquals(0.5f, fadeOutAlpha(positionMs = 9_750, durationMs = 10_000), 1e-4f)
        assertEquals(0.2f, fadeOutAlpha(positionMs = 9_600, durationMs = 10_000), 1e-4f)
        assertEquals(1f, fadeOutAlpha(positionMs = 10_000, durationMs = 10_000))
    }

    @Test
    fun `alpha is clamped past the end`() {
        assertEquals(1f, fadeOutAlpha(positionMs = 12_000, durationMs = 10_000))
    }

    @Test
    fun `a clip shorter than the fade fades over half its length, leaving some picture`() {
        // 400 ms clip: fade window is 200 ms, so the first 200 ms is untouched.
        assertEquals(0f, fadeOutAlpha(positionMs = 200, durationMs = 400))
        assertEquals(0.5f, fadeOutAlpha(positionMs = 300, durationMs = 400), 1e-4f)
        assertEquals(1f, fadeOutAlpha(positionMs = 400, durationMs = 400))
    }

    @Test
    fun `an unknown or empty duration never fades`() {
        assertEquals(0f, fadeOutAlpha(positionMs = 1_000, durationMs = -9_223_372_036_854_775_807L)) // C.TIME_UNSET
        assertEquals(0f, fadeOutAlpha(positionMs = 1_000, durationMs = 0))
    }

    @Test
    fun `a 1 ms clip does not divide by zero`() {
        val a = fadeOutAlpha(positionMs = 1, durationMs = 1)
        assertTrue(a in 0f..1f)
    }

    // ---- selectPlayingIndices ----

    private val previewIndices = setOf(2, 3, 5, 6, 8, 9, 11)

    @Test
    fun `nothing plays while the grid is scrolling`() {
        assertEquals(emptySet<Int>(), selectPlayingIndices((0..12).toList(), { it in previewIndices }, scrolling = true))
    }

    @Test
    fun `only cells with a preview play, first four in grid order`() {
        assertEquals(setOf(2, 3, 5, 6), selectPlayingIndices((0..12).toList(), { it in previewIndices }, scrolling = false))
    }

    @Test
    fun `fewer previews than the cap all play`() {
        assertEquals(setOf(3, 5), selectPlayingIndices((3..5).toList(), { it in previewIndices }, scrolling = false))
    }

    @Test
    fun `no visible previews means nothing plays`() {
        assertEquals(emptySet<Int>(), selectPlayingIndices(listOf(0, 1, 4, 7), { it in previewIndices }, scrolling = false))
    }

    @Test
    fun `the cap is respected when given explicitly`() {
        assertEquals(setOf(2), selectPlayingIndices((0..12).toList(), { it in previewIndices }, scrolling = false, max = 1))
    }
}
