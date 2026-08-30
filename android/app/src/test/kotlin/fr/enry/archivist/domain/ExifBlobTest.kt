package fr.enry.archivist.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExifBlobTest {
    private fun exif(
        make: String? = null,
        model: String? = null,
        serial: String? = null,
        lens: String? = null,
        dateTimeOriginal: String? = null,
        offsetTimeOriginal: String? = null,
        gpsDateTimeUtc: Instant? = null,
    ) = ExifData(
        widthPx = 4032,
        heightPx = 3024,
        cameraMake = make,
        cameraModel = model,
        cameraSerial = serial,
        lens = lens,
        dateTimeOriginal = dateTimeOriginal,
        offsetTimeOriginal = offsetTimeOriginal,
        gpsDateTimeUtc = gpsDateTimeUtc,
    )

    @Test
    fun `every field null produces no blob at all -- nothing worth encrypting`() {
        assertNull(ExifBlob.from(exif()))
    }

    @Test
    fun `a single populated field is enough to produce a blob`() {
        val blob = ExifBlob.from(exif(make = "Canon"))
        assertEquals("Canon", blob?.cameraMake)
        assertNull(blob?.cameraModel)
    }

    @Test
    fun `carries every field through, including the resolved GPS instant as ISO-8601`() {
        val gps = Instant.parse("2026-07-14T00:22:05Z")
        val blob =
            ExifBlob.from(
                exif(
                    make = "Canon",
                    model = "EOS R5",
                    serial = "042024001234",
                    lens = "RF 24-70mm",
                    dateTimeOriginal = "2026:07:14 09:22:05",
                    offsetTimeOriginal = "+09:00",
                    gpsDateTimeUtc = gps,
                ),
            )
        assertEquals("Canon", blob?.cameraMake)
        assertEquals("EOS R5", blob?.cameraModel)
        assertEquals("042024001234", blob?.cameraSerial)
        assertEquals("RF 24-70mm", blob?.lens)
        assertEquals("2026:07:14 09:22:05", blob?.dateTimeOriginal)
        assertEquals("+09:00", blob?.offsetTimeOriginal)
        assertEquals(gps.toString(), blob?.gpsDateTimeUtc)
    }

    @Test
    fun `round-trips through JSON`() {
        val original = ExifBlob(cameraMake = "Canon", dateTimeOriginal = "2026:07:14 09:22:05")
        val json = kotlinx.serialization.json.Json.encodeToString(ExifBlob.serializer(), original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(ExifBlob.serializer(), json)
        assertEquals(original, decoded)
    }
}
