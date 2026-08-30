package fr.enry.archivist.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/** Mirrors `TakenAtSrc` in `src/core/items.ts`. Only the two rungs a *client* can ever
 * produce — `s3-mtime`/`upload` (design.md's rungs 3–4) describe objects that bypass
 * the client entirely, so they can't come out of this ladder. */
enum class TakenAtSrc(val wireValue: String) {
    EXIF("exif"),
    FILE_MTIME("file-mtime"),
}

/** Mirrors `TzSrc` in `src/core/items.ts` — all seven rungs, since every one of them is
 * resolvable client-side per `design.md`'s "Encrypted EXIF" section ("The offset ladder
 * runs on the client at ingest"). */
enum class TzSrc(val wireValue: String) {
    UPLOAD_FORCED("upload-forced"),
    EXIF_OFFSET("exif-offset"),
    GPS("gps"),
    UPLOAD("upload"),
    DEVICE("device"),
    OWNER_DEFAULT("owner-default"),
    ASSUMED_UTC("assumed-utc"),
}

enum class OffsetMode { FORCE, FALLBACK }

/** The `tzOffsetMin`/`offsetMode` pair a batch upload can supply — see "Upload-supplied
 * offset" in `design.md` for why the two modes aren't interchangeable. */
data class UploadOffsetHint(val tzOffsetMin: Int, val mode: OffsetMode)

data class ResolvedTimestamp(
    val takenAt: Instant,
    val takenAtSrc: TakenAtSrc,
    val tzOffsetMin: Int,
    val tzSrc: TzSrc,
)

/**
 * Plan step 2.8: the `takenAt` and UTC-offset ladders from `design.md`'s "Establishing
 * `takenAt`" and "Resolving the UTC offset at ingest", run client-side. Pure function —
 * every input the ladder can use is a parameter, including the clock ([now]), so this
 * is testable with plain fixtures and no device, backend, or real image file.
 *
 * Returns null when nothing usable is found at all (no parseable EXIF timestamp *and*
 * an implausible file mtime) — the caller sends no timestamp hint, leaving the S3-event
 * lambda to fall back to its own server-side rungs (`s3-mtime`/`upload`).
 */
object Timestamps {
    private val EXIF_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val MIN_TAKEN_AT: Instant = Instant.parse("1990-01-01T00:00:00Z")

    fun resolve(
        exif: ExifData?,
        fileMtime: Instant,
        uploadOffset: UploadOffsetHint? = null,
        deviceDefaultOffsetMin: Int? = null,
        homeTz: ZoneId? = null,
        now: Instant = Instant.now(),
    ): ResolvedTimestamp? {
        val naiveLocal = exif?.dateTimeOriginal?.let(::parseNaiveLocal)
        if (naiveLocal != null) {
            val referenceInstant = naiveLocal.toInstant(ZoneOffset.UTC)
            val offset = resolveOffset(exif, naiveLocal, referenceInstant, uploadOffset, deviceDefaultOffsetMin, homeTz)
            val takenAt = naiveLocal.toInstant(ZoneOffset.ofTotalSeconds(offset.tzOffsetMin * 60))
            if (isPlausible(takenAt, now)) {
                return ResolvedTimestamp(takenAt, TakenAtSrc.EXIF, offset.tzOffsetMin, offset.tzSrc)
            }
            // Implausible EXIF timestamp (garbled camera clock, or one of the rungs
            // above computed nonsense from it): fall through exactly as if there had
            // been no DateTimeOriginal at all.
        }

        if (!isPlausible(fileMtime, now)) return null
        val offset = resolveOffset(null, null, fileMtime, uploadOffset, deviceDefaultOffsetMin, homeTz)
        return ResolvedTimestamp(fileMtime, TakenAtSrc.FILE_MTIME, offset.tzOffsetMin, offset.tzSrc)
    }

    private data class Offset(val tzOffsetMin: Int, val tzSrc: TzSrc)

    private fun resolveOffset(
        exif: ExifData?,
        naiveLocal: LocalDateTime?,
        referenceInstant: Instant,
        uploadOffset: UploadOffsetHint?,
        deviceDefaultOffsetMin: Int?,
        homeTz: ZoneId?,
    ): Offset {
        if (uploadOffset?.mode == OffsetMode.FORCE) {
            return Offset(uploadOffset.tzOffsetMin, TzSrc.UPLOAD_FORCED)
        }
        if (naiveLocal != null) {
            exif?.offsetTimeOriginal?.let { raw ->
                parseOffsetMinutes(raw)?.let { return Offset(it, TzSrc.EXIF_OFFSET) }
            }
            exif?.gpsDateTimeUtc?.let { gpsUtc ->
                return Offset(gpsDeltaMinutes(naiveLocal, gpsUtc), TzSrc.GPS)
            }
        }
        if (uploadOffset != null) {
            return Offset(uploadOffset.tzOffsetMin, TzSrc.UPLOAD)
        }
        if (deviceDefaultOffsetMin != null) {
            return Offset(deviceDefaultOffsetMin, TzSrc.DEVICE)
        }
        if (homeTz != null) {
            val minutes = homeTz.rules.getOffset(referenceInstant).totalSeconds / 60
            return Offset(minutes, TzSrc.OWNER_DEFAULT)
        }
        return Offset(0, TzSrc.ASSUMED_UTC)
    }

    private fun parseNaiveLocal(raw: String): LocalDateTime? =
        try {
            LocalDateTime.parse(raw, EXIF_DATETIME_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

    /** `+HH:MM` / `-HH:MM` per EXIF 2.31, plus the bare `Z` some cameras write. */
    private fun parseOffsetMinutes(raw: String): Int? {
        if (raw == "Z") return 0
        val match = OFFSET_PATTERN.matchEntire(raw) ?: return null
        val (sign, hh, mm) = match.destructured
        val minutes = hh.toInt() * 60 + mm.toInt()
        return if (sign == "-") -minutes else minutes
    }

    /** `DateTimeOriginal − GPSDateStamp/GPSTimeStamp`, rounded to the nearest 15
     * minutes (`design.md`) — the naive local value read as if it were UTC, minus the
     * real UTC GPS instant, recovers the local zone's offset from UTC. */
    private fun gpsDeltaMinutes(
        naiveLocal: LocalDateTime,
        gpsUtc: Instant,
    ): Int {
        val naiveAsUtc = naiveLocal.toInstant(ZoneOffset.UTC)
        val rawMinutes = Duration.between(gpsUtc, naiveAsUtc).toMinutes()
        return (rawMinutes / 15.0).roundToInt() * 15
    }

    private fun isPlausible(
        instant: Instant,
        now: Instant,
    ): Boolean = !instant.isBefore(MIN_TAKEN_AT) && !instant.isAfter(now)

    private val OFFSET_PATTERN = Regex("""^([+-])(\d{2}):(\d{2})$""")
}
