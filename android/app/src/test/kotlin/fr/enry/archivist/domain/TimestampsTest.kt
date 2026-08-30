package fr.enry.archivist.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimestampsTest {
    private val now = Instant.parse("2026-08-30T12:00:00Z")
    private val fileMtime = Instant.parse("2024-03-01T09:00:00Z")

    private fun exif(
        dateTimeOriginal: String? = null,
        offsetTimeOriginal: String? = null,
        gpsDateTimeUtc: Instant? = null,
    ) = ExifData(
        widthPx = null,
        heightPx = null,
        cameraMake = null,
        cameraModel = null,
        cameraSerial = null,
        lens = null,
        dateTimeOriginal = dateTimeOriginal,
        offsetTimeOriginal = offsetTimeOriginal,
        gpsDateTimeUtc = gpsDateTimeUtc,
    )

    // -- "Done when" fixtures: EXIF-with-offset, EXIF-with-GPS-only, EXIF-with-neither,
    // no-EXIF-at-all, each resolving to the documented rung. --

    @Test
    fun `EXIF with offset resolves via exif-offset rung`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00", offsetTimeOriginal = "+09:00"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.EXIF, result?.takenAtSrc)
        assertEquals(TzSrc.EXIF_OFFSET, result?.tzSrc)
        assertEquals(9 * 60, result?.tzOffsetMin)
        // 14:30 local at +09:00 is 05:30 UTC the same day.
        assertEquals(Instant.parse("2024-06-15T05:30:00Z"), result?.takenAt)
    }

    @Test
    fun `EXIF with GPS only resolves via gps delta rung`() {
        // Naive local reads 14:30; GPS (true UTC) says 05:32 -- a ~9h delta, rounded to
        // the nearest 15 minutes (design.md's own tolerance for this rung).
        val result =
            Timestamps.resolve(
                exif =
                    exif(
                        dateTimeOriginal = "2024:06:15 14:30:00",
                        gpsDateTimeUtc = Instant.parse("2024-06-15T05:32:00Z"),
                    ),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.EXIF, result?.takenAtSrc)
        assertEquals(TzSrc.GPS, result?.tzSrc)
        assertEquals(9 * 60, result?.tzOffsetMin)
        assertEquals(Instant.parse("2024-06-15T05:30:00Z"), result?.takenAt)
    }

    @Test
    fun `EXIF with neither offset nor GPS falls to assumed-utc`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.EXIF, result?.takenAtSrc)
        assertEquals(TzSrc.ASSUMED_UTC, result?.tzSrc)
        assertEquals(0, result?.tzOffsetMin)
        assertEquals(Instant.parse("2024-06-15T14:30:00Z"), result?.takenAt)
    }

    @Test
    fun `no EXIF at all falls to file mtime and assumed-utc`() {
        val result = Timestamps.resolve(exif = null, fileMtime = fileMtime, now = now)
        assertEquals(TakenAtSrc.FILE_MTIME, result?.takenAtSrc)
        assertEquals(TzSrc.ASSUMED_UTC, result?.tzSrc)
        assertEquals(fileMtime, result?.takenAt)
    }

    // -- The remaining rungs, for thoroughness beyond the four required fixtures. --

    @Test
    fun `upload-forced beats EXIF offset`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00", offsetTimeOriginal = "+09:00"),
                fileMtime = fileMtime,
                uploadOffset = UploadOffsetHint(tzOffsetMin = 120, mode = OffsetMode.FORCE),
                now = now,
            )
        assertEquals(TzSrc.UPLOAD_FORCED, result?.tzSrc)
        assertEquals(120, result?.tzOffsetMin)
        assertEquals(Instant.parse("2024-06-15T12:30:00Z"), result?.takenAt)
    }

    @Test
    fun `EXIF offset beats upload-fallback`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00", offsetTimeOriginal = "+09:00"),
                fileMtime = fileMtime,
                uploadOffset = UploadOffsetHint(tzOffsetMin = 120, mode = OffsetMode.FALLBACK),
                now = now,
            )
        assertEquals(TzSrc.EXIF_OFFSET, result?.tzSrc)
        assertEquals(9 * 60, result?.tzOffsetMin)
    }

    @Test
    fun `upload-fallback wins when EXIF has no offset or GPS`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00"),
                fileMtime = fileMtime,
                uploadOffset = UploadOffsetHint(tzOffsetMin = -300, mode = OffsetMode.FALLBACK),
                now = now,
            )
        assertEquals(TzSrc.UPLOAD, result?.tzSrc)
        assertEquals(-300, result?.tzOffsetMin)
    }

    @Test
    fun `device default wins below upload-fallback`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00"),
                fileMtime = fileMtime,
                deviceDefaultOffsetMin = 60,
                now = now,
            )
        assertEquals(TzSrc.DEVICE, result?.tzSrc)
        assertEquals(60, result?.tzOffsetMin)
    }

    @Test
    fun `owner home timezone wins below device default`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 14:30:00"),
                fileMtime = fileMtime,
                homeTz = ZoneId.of("Europe/Paris"),
                now = now,
            )
        assertEquals(TzSrc.OWNER_DEFAULT, result?.tzSrc)
        // Europe/Paris is UTC+2 (CEST) in June.
        assertEquals(120, result?.tzOffsetMin)
    }

    @Test
    fun `no-EXIF path still consults upload-fallback, device default and home timezone`() {
        val viaUpload =
            Timestamps.resolve(
                exif = null,
                fileMtime = fileMtime,
                uploadOffset = UploadOffsetHint(tzOffsetMin = 30, mode = OffsetMode.FALLBACK),
                now = now,
            )
        assertEquals(TzSrc.UPLOAD, viaUpload?.tzSrc)

        val viaDevice =
            Timestamps.resolve(exif = null, fileMtime = fileMtime, deviceDefaultOffsetMin = 45, now = now)
        assertEquals(TzSrc.DEVICE, viaDevice?.tzSrc)

        val viaHomeTz =
            Timestamps.resolve(exif = null, fileMtime = fileMtime, homeTz = ZoneId.of("Europe/Paris"), now = now)
        assertEquals(TzSrc.OWNER_DEFAULT, viaHomeTz?.tzSrc)
    }

    @Test
    fun `future EXIF timestamp is rejected and falls back to file mtime`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2099:01:01 00:00:00"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.FILE_MTIME, result?.takenAtSrc)
        assertEquals(fileMtime, result?.takenAt)
    }

    @Test
    fun `pre-1990 EXIF timestamp is rejected and falls back to file mtime`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "1975:05:20 08:00:00"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.FILE_MTIME, result?.takenAtSrc)
    }

    @Test
    fun `unparseable EXIF datetime is treated as absent`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "not-a-date"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(TakenAtSrc.FILE_MTIME, result?.takenAtSrc)
    }

    @Test
    fun `both EXIF and file mtime implausible resolves to null`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2099:01:01 00:00:00"),
                fileMtime = Instant.parse("2099-01-01T00:00:00Z"),
                now = now,
            )
        assertNull(result)
    }

    @Test
    fun `GPS delta rounds to nearest 15 minutes`() {
        // 7-minute raw delta should round down to 0, not up to 15.
        val result =
            Timestamps.resolve(
                exif =
                    exif(
                        dateTimeOriginal = "2024:06:15 14:30:00",
                        gpsDateTimeUtc = Instant.parse("2024-06-15T14:23:00Z"),
                    ),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(0, result?.tzOffsetMin)
    }

    @Test
    fun `negative offset is parsed correctly`() {
        val result =
            Timestamps.resolve(
                exif = exif(dateTimeOriginal = "2024:06:15 08:00:00", offsetTimeOriginal = "-05:00"),
                fileMtime = fileMtime,
                now = now,
            )
        assertEquals(-5 * 60, result?.tzOffsetMin)
        assertEquals(Instant.parse("2024-06-15T13:00:00Z"), result?.takenAt)
    }
}
