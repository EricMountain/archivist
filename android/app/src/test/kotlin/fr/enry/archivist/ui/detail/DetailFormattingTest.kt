package fr.enry.archivist.ui.detail

import fr.enry.archivist.data.remote.PlaceDto
import fr.enry.archivist.data.remote.PlaceRefDto
import fr.enry.archivist.data.repo.PhotoDetail
import fr.enry.archivist.data.repo.RenditionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.domain.GeoPoint
import java.util.Locale
import org.junit.jupiter.api.Test

/** Plan step 2.12's pure formatting helpers, pulled out to `internal` visibility
 * specifically so they're testable with no Compose/Android framework involved — same
 * convention as `PhotoEntity.localDate()`. */
class DetailFormattingTest {
    private fun rendition(
        renditionId: String,
        role: String = "display",
        ext: String = "jpg",
        plainBytes: Long = 0,
        path: String = "camera/IMG_1.$ext",
    ) = RenditionSummary(
        renditionId = renditionId,
        role = role,
        path = path,
        ext = ext,
        mime = "image/jpeg",
        s3Key = "raw/owner/photo/$renditionId",
        contentHash = "hmac-sha256:$renditionId",
        bytes = plainBytes,
        plainBytes = plainBytes,
        width = 100,
        height = 100,
        encIv = "iv",
        encChunkSize = 0L,
        addedAt = "2026-08-30T10:00:00.000Z",
    )

    private fun detail(
        renditions: List<RenditionSummary>,
        primaryRend: String? = null,
    ) = PhotoDetail(
        photoId = "p1",
        stem = "IMG_1",
        encDek = "dek",
        takenAt = "2026-08-30T10:00:00.000Z",
        tzOffsetMin = 0,
        takenAtSrc = "exif",
        tzSrc = "gps",
        mime = "image/jpeg",
        width = 100,
        height = 100,
        primaryRend = primaryRend,
        renditionsCount = renditions.size,
        groupSrc = "stem",
        deviceKey = null,
        status = "ready",
        uploadedAt = "2026-08-30T10:00:00.000Z",
        deletedAt = null,
        deletedBy = null,
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

    @Test
    fun `fileName strips the folder, keeping only the basename`() {
        assertEquals("IMG_1.jpg", rendition("r1", path = "camera/IMG_1.jpg").fileName())
    }

    @Test
    fun `fileName with no folder is unchanged`() {
        assertEquals("IMG_1.jpg", rendition("r1", path = "IMG_1.jpg").fileName())
    }

    // --- Geolocation plan step 7: the location row ---

    private val en = Locale.ENGLISH

    private fun place(
        cc: String,
        region: String? = null,
        locality: String? = null,
    ) = PlaceDto(
        cc = cc,
        region = region?.let { PlaceRefDto("$cc.01", it) },
        locality = locality?.let { PlaceRefDto("123", it) },
        src = "geonames-cities500@2026-11-13",
    )

    @Test
    fun `placeLabel drops a region equal to the locality`() {
        assertEquals("Kyoto, Japan", placeLabel(place("JP", region = "Kyoto", locality = "Kyoto"), en))
    }

    @Test
    fun `placeLabel keeps a distinct region, non-ASCII intact`() {
        assertEquals(
            "Arles, Provence-Alpes-Côte d'Azur, France",
            placeLabel(place("FR", region = "Provence-Alpes-Côte d'Azur", locality = "Arles"), en),
        )
    }

    @Test
    fun `placeLabel with a locality and no region`() {
        assertEquals("Stanley, Falkland Islands", placeLabel(place("FK", locality = "Stanley"), en))
    }

    @Test
    fun `placeLabel region only`() {
        assertEquals("Hokkaido, Japan", placeLabel(place("JP", region = "Hokkaido"), en))
    }

    /** sample-data.md A12: every level from a region pack, most specific first. */
    private val argennosc =
        PlaceDto(
            cc = "FR",
            region = PlaceRefDto("FR.84", "Auvergne-Rhône-Alpes"),
            locality = PlaceRefDto("r100101", "Chamonix-Mont-Blanc"),
            sub = PlaceRefDto("n200202", "Argentière"),
            spot = PlaceRefDto("n300303", "Le Planet"),
            areas = listOf(PlaceRefDto("r400404", "Réserve naturelle des Aiguilles Rouges")),
            src = "osm@2026-11-19;geonames-cities500@2026-11-13",
        )

    @Test
    fun `placeLabel with region-pack levels`() {
        assertEquals(
            "Le Planet, Argentière, Chamonix-Mont-Blanc, Auvergne-Rhône-Alpes, France",
            placeLabel(argennosc, en),
        )
    }

    @Test
    fun `placeExtras lists areas then the feature, and is null without either`() {
        assertEquals("Réserve naturelle des Aiguilles Rouges", placeExtras(argennosc))
        assertEquals(
            "Parc national des Écrins (cœur) · Lac de la Muzelle",
            placeExtras(
                PlaceDto(
                    cc = "FR",
                    locality = PlaceRefDto("r500505", "Les Deux Alpes"),
                    areas = listOf(PlaceRefDto("r600606", "Parc national des Écrins (cœur)")),
                    feature = PlaceRefDto("r700707", "Lac de la Muzelle"),
                    src = "osm@2026-11-19;geonames-cities500@2026-11-13",
                ),
            ),
        )
        assertEquals(null, placeExtras(place("JP", region = "Kyoto", locality = "Kyoto")))
    }

    @Test
    fun `placeLabel country only`() {
        assertEquals("Japan", placeLabel(place("JP"), en))
    }

    @Test
    fun `placeLabel renders the country in the given locale`() {
        assertEquals("Kyoto, Japon", placeLabel(place("JP", region = "Kyoto", locality = "Kyoto"), Locale.FRENCH))
    }

    @Test
    fun `countryName falls back to the code for a malformed one`() {
        assertEquals("J1", countryName("J1", en))
    }

    @Test
    fun `formatCoordinates is five decimals, dot separator, whatever the locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("35.01161, 135.76804", formatCoordinates(GeoPoint(35.0116123, 135.768044)))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `formatCoordinates keeps signs and pads`() {
        assertEquals("-51.69200, -57.85000", formatCoordinates(GeoPoint(-51.692, -57.85)))
    }

    @Test
    fun `formatCoordinates rounds half up and never prints negative zero`() {
        assertEquals("35.01162, -135.76805", formatCoordinates(GeoPoint(35.011615, -135.768045)))
        assertEquals("0.00000, 10.00000", formatCoordinates(GeoPoint(-0.000001, 9.999999)))
    }

    @Test
    fun `geoUri uses full precision and escapes the label`() {
        assertEquals(
            "geo:0,0?q=35.0116123,135.768044(Kyoto%2C%20Japan)",
            geoUri(GeoPoint(35.0116123, 135.768044), "Kyoto, Japan"),
        )
    }

    @Test
    fun `geoUri escapes parentheses and non-ASCII`() {
        assertEquals(
            "geo:0,0?q=43.6768,4.6303(Arles%20%28Bouches%29%2C%20C%C3%B4te)",
            geoUri(GeoPoint(43.6768, 4.6303), "Arles (Bouches), Côte"),
        )
    }

    @Test
    fun `geoUri with no label and a tiny or negative coordinate`() {
        assertEquals("geo:0,0?q=-0.00001,-179.5", geoUri(GeoPoint(-0.00001, -179.5), null))
    }

    @Test
    fun `locationRow with a place and coordinates`() {
        val row = locationRow(place("JP", "Kyoto", "Kyoto"), ExifBlob(gpsLat = 35.0116123, gpsLon = 135.768044), en)!!
        assertEquals("Kyoto, Japan", row.primary)
        assertEquals("35.01161, 135.76804", row.secondary)
        assertEquals(GeoPoint(35.0116123, 135.768044), row.point)
        assertEquals("Kyoto, Japan", row.label)
    }

    @Test
    fun `locationRow with coordinates only shows them as the primary line`() {
        val row = locationRow(null, ExifBlob(gpsLat = 47.0, gpsLon = -30.0), en)!!
        assertEquals("47.00000, -30.00000", row.primary)
        assertNull(row.secondary)
        assertNull(row.label)
        assertEquals(GeoPoint(47.0, -30.0), row.point)
    }

    @Test
    fun `locationRow with a place but no coordinates is not tappable`() {
        val row = locationRow(place("JP", region = "Hokkaido"), null, en)!!
        assertEquals("Hokkaido, Japan", row.primary)
        assertNull(row.secondary)
        assertNull(row.point)
        assertNull(locationRow(place("JP"), ExifBlob(cameraMake = "Canon"), en)!!.point)
    }

    @Test
    fun `locationRow with neither is no row`() {
        assertNull(locationRow(null, null, en))
        assertNull(locationRow(null, ExifBlob(cameraMake = "Canon"), en))
        // (0, 0) is a redacted GPS block, not a position (GeoPoint.of).
        assertNull(locationRow(null, ExifBlob(gpsLat = 0.0, gpsLon = 0.0), en))
    }
}
