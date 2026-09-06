package fr.enry.archivist.sync

import android.content.Context
import fr.enry.archivist.testutil.FakeMediaStoreSource
import fr.enry.archivist.testutil.SyntheticMp4
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Plan step 2.18. Covers mime dispatch and the video path end to end (a real
 * synthetic MP4 through [Mp4BoxEditor]). The image path (`ExifInterface.setAttribute`/
 * `saveAttributes`) is deliberately **not** exercised here — see `android/AGENTS.md`'s
 * "That same flag corrupts `android.util.Pair` used *internally* by `ExifInterface`'s
 * *write* path" entry: this test environment's `isReturnDefaultValues` stub breaks
 * `setAttribute` itself, not just fixture generation, so [stripExif][LocationStripper]
 * needs a real device/emulator (`LocationStripperInstrumentedTest`) rather than a bare
 * JVM test.
 */
class LocationStripperTest {
    private fun stripper(mediaStoreSource: FakeMediaStoreSource): LocationStripper {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(Files.createTempDirectory("location-stripper-test").toFile())
        return LocationStripper(context, mediaStoreSource)
    }

    @Test
    fun `returns null for a mime it doesn't know how to strip`() =
        runTest {
            val mediaStoreSource = FakeMediaStoreSource().apply { addFile("b", "B", "content://raw", "IMG.CR3", ByteArray(4)) }

            val result = stripper(mediaStoreSource).strip("content://raw", "image/x-canon-cr3")

            assertNull(result)
        }

    @Test
    fun `strips an mp4's loci box into a new temporary file, leaving the source untouched`() =
        runTest {
            val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.lociBox()))
            val original = SyntheticMp4.file(moov)
            val mediaStoreSource = FakeMediaStoreSource().apply { addFile("b", "B", "content://video", "clip.mp4", original) }

            val result = stripper(mediaStoreSource).strip("content://video", "video/mp4")

            checkNotNull(result)
            val strippedText = String(result.readBytes(), Charsets.ISO_8859_1)
            assertFalse(strippedText.contains("loci"))
            // The fake's own copy of "the original" is a distinct, unmodified byte
            // array -- reading it back proves this class never wrote through
            // openInputStream's source.
            assertNotEquals(strippedText, String(original, Charsets.ISO_8859_1))
            result.delete()
        }

    @Test
    fun `strips a quicktime video the same way`() =
        runTest {
            val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.quicktimeXyzBox()))
            val mediaStoreSource =
                FakeMediaStoreSource().apply { addFile("b", "B", "content://mov", "clip.mov", SyntheticMp4.file(moov)) }

            val result = stripper(mediaStoreSource).strip("content://mov", "video/quicktime")

            checkNotNull(result)
            assertFalse(String(result.readBytes(), Charsets.ISO_8859_1).contains("©xyz"))
            result.delete()
        }
}
