package fr.enry.archivist.ui.detail

import fr.enry.archivist.data.repo.PhotoDetail
import fr.enry.archivist.data.repo.RenditionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Plan step 2.12's pure formatting helpers, pulled out to `internal` visibility
 * specifically so they're testable with no Compose/Android framework involved — same
 * convention as `TimelineViewModel.localDate()`/`toTimelineItems()`. */
class DetailFormattingTest {
    private fun rendition(
        renditionId: String,
        role: String = "display",
        ext: String = "jpg",
        plainBytes: Long = 0,
    ) = RenditionSummary(
        renditionId = renditionId,
        role = role,
        ext = ext,
        mime = "image/jpeg",
        s3Key = "raw/owner/photo/$renditionId",
        bytes = plainBytes,
        plainBytes = plainBytes,
        width = 100,
        height = 100,
        encIv = "iv",
        encChunkSize = 0L,
    )

    private fun detail(
        renditions: List<RenditionSummary>,
        primaryRend: String? = null,
    ) = PhotoDetail(
        photoId = "p1",
        encDek = "dek",
        takenAt = "2026-08-30T10:00:00.000Z",
        tzOffsetMin = 0,
        takenAtSrc = "exif",
        mime = "image/jpeg",
        width = 100,
        height = 100,
        primaryRend = primaryRend,
        cameraMake = null,
        cameraModel = null,
        exifDecryptFailed = false,
        renditions = renditions,
    )

    @Test
    fun `formatDate marks a non-exif source as approximate`() {
        val exact = formatDate("2026-08-30T09:15:00.000Z", tzOffsetMin = 60, approximate = false)
        val approx = formatDate("2026-08-30T09:15:00.000Z", tzOffsetMin = 60, approximate = true)

        assertEquals("30 Aug 2026, 10:15", exact)
        assertEquals("30 Aug 2026, 10:15 (approximate)", approx)
    }

    @Test
    fun `formatDate uses the given offset, not UTC`() {
        // 23:30 UTC on the 30th, at +02:00, is 01:30 local on the 31st.
        val formatted = formatDate("2026-08-30T23:30:00.000Z", tzOffsetMin = 120, approximate = false)
        assertEquals("31 Aug 2026, 01:30", formatted)
    }

    @Test
    fun `formatBytes scales to the right unit`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2 * 1024 * 1024))
    }

    @Test
    fun `raw role is labelled RAW regardless of extension`() {
        assertEquals("RAW", rendition("r1", role = "raw", ext = "cr3").label())
        assertEquals("RAW", rendition("r1", role = "raw", ext = "arw").label())
    }

    @Test
    fun `known display extensions get a friendly label`() {
        assertEquals("JPEG", rendition("r1", ext = "jpg").label())
        assertEquals("HEIC", rendition("r1", ext = "heic").label())
    }

    @Test
    fun `unknown extension falls back to the raw extension, uppercased`() {
        assertEquals("XYZ", rendition("r1", ext = "xyz").label())
    }

    @Test
    fun `primarySizeBytes prefers the primary rendition`() {
        val primary = rendition("r1", plainBytes = 1_000)
        val other = rendition("r2", plainBytes = 50_000_000)
        assertEquals(1_000L, primarySizeBytes(detail(listOf(primary, other), primaryRend = "r1")))
    }

    @Test
    fun `primarySizeBytes falls back to the largest rendition when no primary is known`() {
        val small = rendition("r1", plainBytes = 1_000)
        val large = rendition("r2", plainBytes = 50_000_000)
        assertEquals(50_000_000L, primarySizeBytes(detail(listOf(small, large), primaryRend = null)))
    }

    @Test
    fun `primarySizeBytes is null with no renditions at all`() {
        assertNull(primarySizeBytes(detail(emptyList())))
    }
}
