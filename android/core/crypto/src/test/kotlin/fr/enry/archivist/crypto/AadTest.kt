package fr.enry.archivist.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AadTest {
    private val photoId = "01K5A2Q8ZCV1D9KXM3BQNR7T2F"

    private fun aad(ref: ObjectRef) = String(Aad.of(photoId, ref), Charsets.UTF_8)

    @Test
    fun `preview encodes as p with no qualifier`() {
        assertEquals("archivist:1:$photoId:p", aad(ObjectRef.Preview))
    }

    @Test
    fun `preview is never the same context as any thumbnail size or the exif blob`() {
        val preview = aad(ObjectRef.Preview)
        for (size in listOf(256, 1024, 2048)) assertNotEquals(preview, aad(ObjectRef.Thumbnail(size)))
        assertNotEquals(preview, aad(ObjectRef.Exif))
    }

    @Test
    fun `existing object refs are unchanged`() {
        assertEquals("archivist:1:$photoId:t:1024", aad(ObjectRef.Thumbnail(1024)))
        assertEquals("archivist:1:$photoId:x", aad(ObjectRef.Exif))
        assertEquals("archivist:1:$photoId:r:R1", aad(ObjectRef.Rendition("R1")))
    }
}
