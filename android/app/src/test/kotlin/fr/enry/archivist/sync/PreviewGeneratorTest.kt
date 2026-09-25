package fr.enry.archivist.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [previewDimensions]/[needsTrim] are the Android-free parts of the video preview
 * clip; the transcode itself needs a real transformer and is device-verified only. */
class PreviewGeneratorTest {
    @Test
    fun `720p landscape scales to 200 wide and an even height`() {
        // 1280x720 -> 200 x 112.5 -> 113 -> rounded down to the even 112.
        assertEquals(200 to 112, previewDimensions(1280, 720))
    }

    @Test
    fun `portrait is the transpose`() {
        assertEquals(112 to 200, previewDimensions(720, 1280))
    }

    @Test
    fun `4K scales down to the same 200 px longest edge`() {
        assertEquals(200 to 112, previewDimensions(3840, 2160))
    }

    @Test
    fun `square source stays square at 200`() {
        assertEquals(200 to 200, previewDimensions(1080, 1080))
    }

    @Test
    fun `a source already under 200 px is never upscaled`() {
        assertEquals(160 to 90, previewDimensions(160, 90))
    }

    @Test
    fun `an odd-sized small source is rounded down to even`() {
        assertEquals(158 to 88, previewDimensions(159, 89))
    }

    @Test
    fun `a degenerate sliver never rounds down to zero`() {
        val (w, h) = previewDimensions(4000, 1)
        assertEquals(200, w)
        assertTrue(h >= 2 && h % 2 == 0)
    }

    @Test
    fun `every produced dimension is even and within the longest-edge cap`() {
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 999 to 1001, 333 to 777, 4096 to 2160)) {
            val (pw, ph) = previewDimensions(w, h)
            assertTrue(pw % 2 == 0 && ph % 2 == 0, "$w x $h -> $pw x $ph")
            assertTrue(maxOf(pw, ph) <= PREVIEW_MAX_EDGE, "$w x $h -> $pw x $ph")
        }
    }

    @Test
    fun `only a source longer than a minute is trimmed`() {
        assertFalse(needsTrim(null))
        assertFalse(needsTrim(0))
        assertFalse(needsTrim(59_999))
        assertFalse(needsTrim(60_000))
        assertTrue(needsTrim(60_001))
        assertTrue(needsTrim(600_000))
    }
}
