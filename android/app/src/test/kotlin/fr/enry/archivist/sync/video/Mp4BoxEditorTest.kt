package fr.enry.archivist.sync.video

import fr.enry.archivist.testutil.SyntheticMp4
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plan step 2.18 — synthetic-fixture coverage for the box-walking logic itself; no
 * real device recording involved (see this class's own doc for why that's enough for
 * the walking logic, and design.md's "Mechanism, video" for what a real fixture would
 * still be worth adding for). Pure JVM, no Android framework dependency at all.
 */
class Mp4BoxEditorTest {
    private fun tempFile(bytes: ByteArray): java.io.File {
        val file = Files.createTempFile("mp4-box-editor-test", ".mp4").toFile()
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private fun asIso88591(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `strips a 3GPP loci box under moov udta, leaving file length unchanged`() {
        val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.lociBox()))
        val original = SyntheticMp4.file(moov)
        val file = tempFile(original)

        Mp4BoxEditor.stripLocation(file)

        val stripped = file.readBytes()
        assertEquals(original.size, stripped.size)
        assertFalse(asIso88591(stripped).contains("loci"))
        assertTrue(asIso88591(stripped).contains("free"))
    }

    @Test
    fun `strips a QuickTime © xyz box under moov udta`() {
        val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.quicktimeXyzBox()))
        val file = tempFile(SyntheticMp4.file(moov))

        Mp4BoxEditor.stripLocation(file)

        val stripped = file.readBytes()
        assertFalse(asIso88591(stripped).contains("©xyz"))
        // The ISO 6709 string itself must be gone too, not just the box's own tag --
        // a name/type swap alone would leave the coordinates sitting in the payload.
        assertFalse(asIso88591(stripped).contains("139.5678"))
    }

    @Test
    fun `strips the matching ilst entry via the ISO FullBox meta scheme, leaving other keys untouched`() {
        val keys = SyntheticMp4.keysBox("com.apple.quicktime.make", "com.apple.quicktime.location.ISO6709")
        val makeItem = SyntheticMp4.ilstItem(1, payload = "Pixel 9".toByteArray())
        val locationItem = SyntheticMp4.ilstItem(2, payload = "+35.1234+139.5678/".toByteArray())
        val meta = SyntheticMp4.isoMetaBox(keys, SyntheticMp4.ilstBox(makeItem, locationItem))
        val moov = SyntheticMp4.moovBox(meta)
        val file = tempFile(SyntheticMp4.file(moov))

        Mp4BoxEditor.stripLocation(file)

        val strippedText = asIso88591(file.readBytes())
        assertFalse(strippedText.contains("139.5678"))
        assertTrue(strippedText.contains("Pixel 9")) // the unrelated key survives untouched
    }

    @Test
    fun `strips the matching ilst entry via the QuickTime plain meta scheme`() {
        val keys = SyntheticMp4.keysBox("com.apple.quicktime.location.ISO6709")
        val locationItem = SyntheticMp4.ilstItem(1, payload = "+35.1234+139.5678/".toByteArray())
        val meta = SyntheticMp4.quicktimeMetaBox(keys, SyntheticMp4.ilstBox(locationItem))
        val moov = SyntheticMp4.moovBox(meta)
        val file = tempFile(SyntheticMp4.file(moov))

        Mp4BoxEditor.stripLocation(file)

        assertFalse(asIso88591(file.readBytes()).contains("139.5678"))
    }

    @Test
    fun `a file with no location box at all is left byte-for-byte unchanged`() {
        val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox())
        val original = SyntheticMp4.file(moov)
        val file = tempFile(original)

        Mp4BoxEditor.stripLocation(file)

        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `strips correctly when moov sits after mdat`() {
        val moov = SyntheticMp4.moovBox(SyntheticMp4.udtaBox(SyntheticMp4.lociBox()))
        val mdat = SyntheticMp4.mdatBox(size = 32)
        val original = SyntheticMp4.file(moov, moovFirst = false, mdat = mdat)
        val file = tempFile(original)

        Mp4BoxEditor.stripLocation(file)

        val stripped = file.readBytes()
        assertEquals(original.size, stripped.size)
        assertFalse(asIso88591(stripped).contains("loci"))
        // mdat's own bytes must be untouched and at the same offset -- nothing about
        // stripping a box inside moov may perturb anything outside it.
        val ftypSize = SyntheticMp4.ftypBox().size
        val mdatOffsetInOriginal = ftypSize
        assertArrayEquals(
            mdat,
            stripped.copyOfRange(mdatOffsetInOriginal, mdatOffsetInOriginal + mdat.size),
        )
    }
}
