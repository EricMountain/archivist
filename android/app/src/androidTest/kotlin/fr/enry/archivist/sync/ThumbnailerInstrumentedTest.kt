package fr.enry.archivist.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.testutil.InsertedMedia
import fr.enry.archivist.testutil.MediaStoreFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan step 2.10 (this class replaces the throwaway `ThumbnailerInstrumentedTest` step
 * 2.9's own status note describes deleting after one run — kept permanently this time,
 * per the plan's request for lasting on-device coverage rather than one-off spikes).
 *
 * [AndroidThumbnailer] against the real `ImageDecoder` — `ThumbnailerTest` (JVM) already
 * covers `targetDimensions`/`sampleSizeFor`'s pure arithmetic; this is specifically
 * "does decoding and downsampling a real image actually work," which needs a real
 * decoder no JVM fake can stand in for.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val thumbnailer: Thumbnailer = AndroidThumbnailer(context)
    private val inserted = mutableListOf<InsertedMedia>()

    @After
    fun tearDown() {
        inserted.forEach { MediaStoreFixtures.delete(context, it) }
    }

    @Test
    fun generatesAllThreeSizesAsValidWebpFromARealDecodedImage() =
        runBlocking {
            // Larger than every rung, so all three actually downsample rather than
            // trivially passing an already-small source through unchanged.
            val media = MediaStoreFixtures.insertJpeg(context, "thumbnailer_instrumented_${System.nanoTime()}.jpg", width = 3000, height = 2000)
            inserted += media

            val thumbnails = thumbnailer.generate(media.contentUri)

            assertEquals(Thumbnailer.SIZES.size, thumbnails.size)
            for (thumb in thumbnails) {
                assertTrue("longest edge of ${thumb.width}x${thumb.height} should be <= ${thumb.longestEdge}", maxOf(thumb.width, thumb.height) <= thumb.longestEdge)
                assertTrue("aspect ratio should be preserved (3:2 source)", thumb.width > thumb.height)
                assertTrue("non-empty WebP bytes", thumb.bytes.isNotEmpty())
                // RIFF....WEBP header -- confirms real WebP bytes, not just "some bytes".
                val header = String(thumb.bytes.copyOfRange(0, 4), Charsets.US_ASCII)
                val format = String(thumb.bytes.copyOfRange(8, 12), Charsets.US_ASCII)
                assertEquals("RIFF", header)
                assertEquals("WEBP", format)
            }
        }

    @Test
    fun neverUpscalesASourceSmallerThanEveryRung() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "thumbnailer_instrumented_small_${System.nanoTime()}.jpg", width = 64, height = 48)
            inserted += media

            val thumbnails = thumbnailer.generate(media.contentUri)

            for (thumb in thumbnails) {
                assertEquals(64, thumb.width)
                assertEquals(48, thumb.height)
            }
        }
}
