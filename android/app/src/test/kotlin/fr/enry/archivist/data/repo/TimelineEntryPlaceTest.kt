package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.remote.TimelineEntryDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `GET /photos`' `place` → [fr.enry.archivist.data.local.db.PhotoEntity]'s flattened
 * `placeCc`/`placeName`/`placeKey` (geolocation plan step 6). */
class TimelineEntryPlaceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(place: String?): TimelineEntryDto =
        json.decodeFromString(
            """
            {"photoId":"01J0000000000000000000000A","takenAt":"2026-07-14T03:00:00.000Z",
             "thumbs":{},"encDek":"d","encKeyId":"k","width":1,"height":1,
             "mime":"image/jpeg","tzOffsetMin":540,"status":"ready"
             ${if (place != null) ""","place":$place""" else ""}}
            """.trimIndent(),
        )

    @Test
    fun `a full place maps to the locality`() {
        val e =
            entry(
                """{"cc":"JP","region":{"id":"JP.22","name":"Kyoto"},
                   "locality":{"id":"1857910","name":"Kyoto"},"src":"geonames-cities500@2026-11-13"}""",
            ).toEntity()
        assertEquals(Triple("JP", "Kyoto", "1857910"), Triple(e.placeCc, e.placeName, e.placeKey))
    }

    @Test
    fun `a locality without a region maps to the locality`() {
        val e = entry("""{"cc":"FK","locality":{"id":"3426691","name":"Stanley"},"src":"s"}""").toEntity()
        assertEquals(Triple("FK", "Stanley", "3426691"), Triple(e.placeCc, e.placeName, e.placeKey))
    }

    @Test
    fun `a region-only place maps to the region`() {
        val e = entry("""{"cc":"JP","region":{"id":"JP.12","name":"Hokkaido"},"src":"s"}""").toEntity()
        assertEquals(Triple("JP", "Hokkaido", "JP.12"), Triple(e.placeCc, e.placeName, e.placeKey))
    }

    @Test
    fun `a country-only place has a cc and no name or key`() {
        val e = entry("""{"cc":"JP","src":"s"}""").toEntity()
        assertEquals(Triple("JP", null, null), Triple(e.placeCc, e.placeName, e.placeKey))
    }

    @Test
    fun `no place maps to nulls`() {
        val e = entry(null).toEntity()
        assertNull(e.placeCc)
        assertNull(e.placeName)
        assertNull(e.placeKey)
    }

    @Test
    fun `the place columns survive an upsert, and a re-fetch without a place clears them`() =
        runTest {
            val db = buildTestDatabase()
            val dao = db.photoDao()
            val located = entry("""{"cc":"FK","locality":{"id":"3426691","name":"Stanley"},"src":"s"}""").toEntity()
            dao.upsertAll(listOf(located))
            assertEquals(located, dao.getByPhotoId(located.photoId))
            dao.upsertAll(listOf(entry(null).toEntity()))
            assertNull(dao.getByPhotoId(located.photoId)!!.placeKey)
            db.close()
        }
}
