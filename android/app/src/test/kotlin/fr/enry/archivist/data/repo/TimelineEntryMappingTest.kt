package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.remote.PhotosPageResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `GET /photos`'s wire JSON -> [fr.enry.archivist.data.local.db.PhotoEntity], for the
 * video preview clip's `preview` attribute (server side: `timelineEntryDto` in `dto.ts`). */
class TimelineEntryMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun page(extra: String) =
        json.decodeFromString<PhotosPageResponse>(
            """
            {"items":[{"photoId":"p1","takenAt":"2026-07-16T04:15:33.000Z",
              "thumbs":{"256":{"bucket":"b","key":"th/o/p1/256","iv":"i","bytes":10}},
              $extra
              "encDek":"d","encKeyId":"mk-1","width":3840,"height":2160,"mime":"video/mp4",
              "tzOffsetMin":540,"status":"ready"}]}
            """.trimIndent(),
        )

    @Test
    fun `a projected preview is mapped onto the entity`() {
        val entity =
            page(""""preview":{"bucket":"b","key":"th/o/p1/preview","iv":"pi","bytes":1500000},""")
                .items.single().toEntity()

        assertEquals(ThumbEntry("b", "th/o/p1/preview", "pi", 1_500_000), entity.preview)
        assertEquals(setOf(256), entity.thumbs.keys)
    }

    @Test
    fun `an entry with no preview key maps to a null preview, not a failure`() {
        assertNull(page("").items.single().toEntity().preview)
    }
}
