package fr.enry.archivist.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [targetDimensions]/[sampleSizeFor] are the only Android-free parts of plan step 2.9
 * (Thumbnails) -- everything else needs a real [android.graphics.ImageDecoder], which
 * has no fake and doesn't run on a bare JVM (see android/AGENTS.md's ExifInterface
 * entries for the same class of constraint). The actual decode/encode path is only
 * verified live, on a device/emulator -- see STATUS.md for what that pass covered.
 */
class ThumbnailerTest {
    @Test
    fun `landscape source scales down to the target longest edge`() {
        val (w, h) = targetDimensions(width = 8688, height = 5792, longestEdge = 2048)
        assertEquals(2048, w)
        assertEquals(1365, h)
    }

    @Test
    fun `portrait source is the transpose of landscape`() {
        val (w, h) = targetDimensions(width = 5792, height = 8688, longestEdge = 2048)
        assertEquals(1365, w)
        assertEquals(2048, h)
    }

    @Test
    fun `never upscales a source smaller than the rung`() {
        val (w, h) = targetDimensions(width = 200, height = 100, longestEdge = 256)
        assertEquals(200, w)
        assertEquals(100, h)
    }

    @Test
    fun `exact match at the rung is left alone`() {
        val (w, h) = targetDimensions(width = 2048, height = 1536, longestEdge = 2048)
        assertEquals(2048, w)
        assertEquals(1536, h)
    }

    @Test
    fun `square source stays square`() {
        val (w, h) = targetDimensions(width = 4000, height = 4000, longestEdge = 1024)
        assertEquals(1024, w)
        assertEquals(1024, h)
    }

    @Test
    fun `sample size 1 when source is already at or below the target`() {
        assertEquals(1, sampleSizeFor(width = 200, height = 100, longestEdge = 256))
        assertEquals(1, sampleSizeFor(width = 2048, height = 1536, longestEdge = 2048))
    }

    @Test
    fun `sample size divides a 50 megapixel source down toward 2048 without going under`() {
        // 8688x5792 is a real 50 MP sensor's output (Xiaomi/Samsung 108/50 MP class).
        val sampleSize = sampleSizeFor(width = 8688, height = 5792, longestEdge = 2048)
        assertEquals(4, sampleSize)
        // The decoded size at this sample size must still be >= the target -- the
        // final precise resize in AndroidThumbnailer only ever scales down, never up.
        assertTrue(8688 / sampleSize >= 2048)
    }

    @Test
    fun `sample size never goes below 1`() {
        assertEquals(1, sampleSizeFor(width = 300, height = 200, longestEdge = 2048))
    }

    @Test
    fun `poster frame time is one second into a video at least two seconds long`() {
        assertEquals(1_000_000L, posterFrameTimeUs(durationMs = 10_000))
    }

    @Test
    fun `poster frame time never exceeds one second in`() {
        assertEquals(1_000_000L, posterFrameTimeUs(durationMs = 60_000))
    }

    @Test
    fun `poster frame time stays inside a clip shorter than two seconds`() {
        assertEquals(400_000L, posterFrameTimeUs(durationMs = 800))
    }

    @Test
    fun `poster frame time is zero when duration is unknown`() {
        assertEquals(0L, posterFrameTimeUs(durationMs = null))
        assertEquals(0L, posterFrameTimeUs(durationMs = 0))
    }

    @Test
    fun `candidates start at the poster instant then probe a quarter half and three quarters in`() {
        assertEquals(
            listOf(1_000_000L, 2_500_000L, 5_000_000L, 7_500_000L),
            posterFrameCandidatesUs(durationMs = 10_000),
        )
    }

    @Test
    fun `candidates collapse duplicates for a very short clip`() {
        // 4 ms: poster instant and the half-way probe are both 2000 us; only one survives.
        assertEquals(listOf(2_000L, 1_000L, 3_000L), posterFrameCandidatesUs(durationMs = 4))
    }

    @Test
    fun `unknown duration only probes frame zero`() {
        assertEquals(listOf(0L), posterFrameCandidatesUs(durationMs = null))
        assertEquals(listOf(0L), posterFrameCandidatesUs(durationMs = 0))
    }

    @Test
    fun `an all black frame is mostly dark`() {
        assertTrue(isMostlyDark(IntArray(256) { 0xFF000000.toInt() }))
    }

    @Test
    fun `a mid grey frame is not dark`() {
        assertTrue(!isMostlyDark(IntArray(256) { 0xFF808080.toInt() }))
    }

    @Test
    fun `mean luma weights green above red above blue`() {
        val red = meanLuma(intArrayOf(0xFFFF0000.toInt()))
        val green = meanLuma(intArrayOf(0xFF00FF00.toInt()))
        val blue = meanLuma(intArrayOf(0xFF0000FF.toInt()))
        assertTrue(green > red && red > blue)
    }

    @Test
    fun `mean luma of an empty sample is zero`() {
        assertEquals(0.0, meanLuma(IntArray(0)))
    }
}
