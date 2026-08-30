package fr.enry.archivist.domain

import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.io.path.createTempFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Runs the real `androidx.exifinterface` parser — no Robolectric or device needed,
 * since the library's *read* path is plain `java.io` (see `android/AGENTS.md`'s pattern
 * for JVM-safe real implementations over mocking a framework class).
 *
 * `canon-with-gps.jpg` (`src/test/resources/exif-fixtures/`) is a real, if synthetic,
 * JPEG with known EXIF/GPS tags — committed rather than generated at test time. A first
 * draft tried generating it in-test with `ExifInterface`'s own writer, and that surfaced
 * a genuine environment gotcha worth recording rather than working around silently:
 * `app/build.gradle.kts`'s `testOptions.unitTests.isReturnDefaultValues = true` (needed
 * so `ExifInterface`'s static initializer, which calls `android.util.Log.isLoggable`,
 * doesn't throw "not mocked") also makes the *stub* `android.util.Pair` class used
 * internally by `ExifInterface.setAttribute()`'s format-guessing construct with both
 * fields null instead of running its real constructor — corrupting only the *write*
 * path, silently, with a confusing NPE several calls downstream. The read path this
 * test actually exercises never touches that code, so it's unaffected; the fixture was
 * generated once, outside this environment, with Python's Pillow
 * (`Image.Exif`/`get_ifd`), and regenerating it needs the same tool, not this library's
 * writer.
 */
class ExifExtractorTest {
    private lateinit var file: File

    @AfterEach
    fun tearDown() {
        if (::file.isInitialized) file.delete()
    }

    private fun baselineJpeg(
        width: Int = 64,
        height: Int = 48,
    ): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val tmp = createTempFile(prefix = "exif-test", suffix = ".jpg").toFile()
        ImageIO.write(image, "jpg", tmp)
        return tmp
    }

    private fun fixtureStream() =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("exif-fixtures/canon-with-gps.jpg")) {
            "missing test fixture exif-fixtures/canon-with-gps.jpg"
        }

    @Test
    fun `plain JPEG with no EXIF segment reports dimensions from the container, nothing else`() {
        file = baselineJpeg(width = 64, height = 48)

        val data = file.inputStream().use { ExifExtractor.extract(it) }

        assertEquals(64, data.widthPx)
        assertEquals(48, data.heightPx)
        assertNull(data.cameraMake)
        assertNull(data.cameraModel)
        assertNull(data.cameraSerial)
        assertNull(data.lens)
        assertNull(data.dateTimeOriginal)
        assertNull(data.offsetTimeOriginal)
        assertNull(data.gpsDateTimeUtc)
    }

    @Test
    fun `camera, timestamp and GPS tags are all read from a real EXIF-bearing JPEG`() {
        val data = fixtureStream().use { ExifExtractor.extract(it) }

        assertEquals(64, data.widthPx)
        assertEquals(48, data.heightPx)
        assertEquals("Canon", data.cameraMake)
        assertEquals("EOS R5", data.cameraModel)
        assertEquals("042024001234", data.cameraSerial)
        assertEquals("RF 24-105mm F4 L IS USM", data.lens)
        assertEquals("2024:06:15 14:30:00", data.dateTimeOriginal)
        assertEquals("+09:00", data.offsetTimeOriginal)
        assertEquals(Instant.parse("2024-06-15T05:32:00Z"), data.gpsDateTimeUtc)
    }

    @Test
    fun `image with no GPS tags reports null gpsDateTimeUtc, not a sentinel instant`() {
        file = baselineJpeg()

        val data = file.inputStream().use { ExifExtractor.extract(it) }

        assertNull(data.gpsDateTimeUtc)
    }

    @Test
    fun `image width falls back to the JPEG SOF-derived ImageWidth when PixelXDimension is absent`() {
        // The fixture has no PixelXDimension/PixelYDimension tags at all -- confirming
        // it still reports real dimensions proves the fallback in
        // ExifExtractor.positiveDimension actually fires, not just the happy path.
        val data = fixtureStream().use { ExifExtractor.extract(it) }

        assertEquals(64, data.widthPx)
        assertEquals(48, data.heightPx)
    }

    @Test
    fun `deviceKey normalises and joins make, model and serial`() {
        assertEquals(
            "canon|eos r5|042024001234",
            ExifExtractor.deviceKey("Canon", "EOS R5", "042024001234"),
        )
    }

    @Test
    fun `deviceKey collapses internal whitespace and lowercases`() {
        assertEquals("canon|eos r5|-", ExifExtractor.deviceKey(" Canon ", "EOS   R5", null))
    }

    @Test
    fun `deviceKey writes a dash for missing parts`() {
        assertEquals("-|-|-", ExifExtractor.deviceKey(null, null, null))
        assertEquals("pixel 9|-|-", ExifExtractor.deviceKey("Pixel 9", "", null))
    }

    @Test
    fun `mimeFromDisplayName maps known extensions`() {
        assertEquals("image/jpeg", ExifExtractor.mimeFromDisplayName("IMG_1.JPG"))
        assertEquals("image/heic", ExifExtractor.mimeFromDisplayName("IMG_2.heic"))
        assertEquals("image/x-canon-cr3", ExifExtractor.mimeFromDisplayName("IMG_1.CR3"))
        assertEquals("video/quicktime", ExifExtractor.mimeFromDisplayName("MVIMG_1.MOV"))
    }

    @Test
    fun `mimeFromDisplayName returns null for unknown or missing extensions`() {
        assertNull(ExifExtractor.mimeFromDisplayName("no-extension"))
        assertNull(ExifExtractor.mimeFromDisplayName("weird.bin"))
    }
}
