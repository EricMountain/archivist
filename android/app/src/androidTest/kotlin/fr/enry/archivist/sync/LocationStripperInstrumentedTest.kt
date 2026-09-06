package fr.enry.archivist.sync

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.testutil.InsertedMedia
import fr.enry.archivist.testutil.MediaStoreFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan step 2.18's image-side mechanism, on a real device — see `android/AGENTS.md`'s
 * "That same flag corrupts `android.util.Pair`..." entry for why
 * `LocationStripper.strip`'s `ExifInterface.setAttribute`/`saveAttributes` write path
 * cannot be exercised in a bare JVM test at all (`isReturnDefaultValues` breaks it, not
 * just fixture generation the way it does for the read path). Real GPS is written
 * on-device first — this test environment has no such stub bug — then stripped, then
 * read back.
 */
@RunWith(AndroidJUnit4::class)
class LocationStripperInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mediaStoreSource: MediaStoreSource = AndroidMediaStoreSource(context)
    private val stripper = LocationStripper(context, mediaStoreSource)
    private val inserted = mutableListOf<InsertedMedia>()

    @After
    fun tearDown() {
        inserted.forEach { MediaStoreFixtures.delete(context, it) }
    }

    // Block body, not an expression body ending in Unit? -- android/AGENTS.md's
    // "A JUnit4 @Before/@After method's inferred return type must be exactly Unit"
    // entry applies to @Test methods too: this used to end with `stripped.delete()`
    // (returns Boolean), which silently made the whole method's inferred type
    // Boolean and failed to load as a test at all ("should be void"), not a compile
    // error.
    @Test
    fun stripsRealGpsFromAJpegWithoutTouchingTheOriginal() {
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "location_stripper_instrumented_${System.nanoTime()}.jpg")
            inserted += media
            writeRealGps(media)

            val stripped = stripper.strip(media.contentUri, "image/jpeg")

            checkNotNull(stripped)
            val strippedExif = ExifInterface(stripped.path)
            assertNull("stripped copy must have no GPS lat/long", strippedExif.latLong)

            val originalExif = context.contentResolver.openFileDescriptor(media.uri, "r")!!.use {
                ExifInterface(it.fileDescriptor)
            }
            assertTrue("the original on device must be untouched", originalExif.latLong != null)

            stripped.delete()
        }
    }

    private fun writeRealGps(media: InsertedMedia) {
        context.contentResolver.openFileDescriptor(media.uri, "rw")!!.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.setLatLong(35.6812, 139.7671)
            exif.saveAttributes()
        }
    }
}
