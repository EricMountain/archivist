package fr.enry.archivist.domain

import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Everything plan step 2.8's timestamp/offset ladders ([Timestamps]) need out of a
 * file's EXIF, plus the handful of other fields `design.md`'s `#META`/`R#` items store
 * ([widthPx]/[heightPx], camera identity). Deliberately holds the *raw* tag strings
 * (`dateTimeOriginal`, `offsetTimeOriginal`) rather than anything pre-parsed — that's
 * what lets [Timestamps] be tested against plain literal fixtures with no real image
 * bytes involved, and keeps the one place that must get EXIF's on-disk formats right
 * ([ExifExtractor]) separate from the ladder's own arithmetic.
 */
data class ExifData(
    val widthPx: Int?,
    val heightPx: Int?,
    val cameraMake: String?,
    val cameraModel: String?,
    val cameraSerial: String?,
    val lens: String?,
    /** Raw `DateTimeOriginal`: naive local time, `yyyy:MM:dd HH:mm:ss`, no zone. */
    val dateTimeOriginal: String?,
    /** Raw `OffsetTimeOriginal`, e.g. `+02:00`, `-05:00`, or `Z`. Meaningless without
     * [dateTimeOriginal] to apply it to. */
    val offsetTimeOriginal: String?,
    /** `GPSDateStamp`/`GPSTimeStamp` combined into a UTC instant. Read via
     * [ExifInterface.getGpsDateTime] rather than the two raw tags separately: EXIF
     * stores `GPSTimeStamp` as three RATIONAL values, not the `HH:mm:ss` string its
     * name suggests, and `getGpsDateTime` is the library's own tested parser for
     * that. */
    val gpsDateTimeUtc: Instant?,
)

/**
 * What plan step 2.10 encrypts and uploads as `exifEnc`: a JSON summary of the fields
 * [ExifData] already extracted, rather than the raw EXIF binary segment — nothing
 * downstream needs the original bytes, only these same values (2.12's photo-detail
 * screen reads them back the same way [fr.enry.archivist.domain.Timestamps] does here).
 * `design.md`'s "Encrypted EXIF" specifies *that* the blob is encrypted, not its exact
 * shape; this is that choice, made concrete. [gpsDateTimeUtc] is an ISO-8601 string
 * since kotlinx.serialization has no built-in serializer for `java.time` types.
 */
@Serializable
data class ExifBlob(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val cameraSerial: String? = null,
    val lens: String? = null,
    val dateTimeOriginal: String? = null,
    val offsetTimeOriginal: String? = null,
    val gpsDateTimeUtc: String? = null,
) {
    companion object {
        /** Null when every field would be null — nothing worth encrypting. */
        fun from(exif: ExifData): ExifBlob? {
            val blob =
                ExifBlob(
                    cameraMake = exif.cameraMake,
                    cameraModel = exif.cameraModel,
                    cameraSerial = exif.cameraSerial,
                    lens = exif.lens,
                    dateTimeOriginal = exif.dateTimeOriginal,
                    offsetTimeOriginal = exif.offsetTimeOriginal,
                    gpsDateTimeUtc = exif.gpsDateTimeUtc?.toString(),
                )
            return if (blob == ExifBlob()) null else blob
        }
    }
}

/**
 * The client-side half of "EXIF extraction, dimensions, MIME sniffing" (`design.md`,
 * "What the server can no longer do") — a Lambda can't read pixels, so this has to run
 * on-device before upload. Stateless: `androidx.exifinterface`'s `ExifInterface` does
 * the actual byte parsing, this just picks the tags plan step 2.8 needs off it.
 */
object ExifExtractor {
    /** Doesn't throw on a well-formed image with no EXIF segment at all (every field
     * comes back null except whatever the container format itself reveals, like
     * dimensions) — only on a stream `ExifInterface` can't parse as an image at all,
     * which callers should treat as "not decodable," not "no metadata." */
    fun extract(input: InputStream): ExifData {
        val exif = ExifInterface(input)
        val gpsMillis = exif.getGpsDateTime()
        return ExifData(
            widthPx = positiveDimension(exif, ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_IMAGE_WIDTH),
            heightPx = positiveDimension(exif, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_IMAGE_LENGTH),
            cameraMake = exif.attribute(ExifInterface.TAG_MAKE),
            cameraModel = exif.attribute(ExifInterface.TAG_MODEL),
            cameraSerial = exif.attribute(ExifInterface.TAG_BODY_SERIAL_NUMBER),
            lens = exif.attribute(ExifInterface.TAG_LENS_MODEL),
            dateTimeOriginal = exif.attribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            offsetTimeOriginal = exif.attribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            // getGpsDateTime() is genuinely nullable in this version (1.4.2) --
            // confirmed by the compiler, not assumed: an earlier draft treated it as a
            // Long with a negative sentinel and androidx's own @Nullable annotation
            // caught the bug at compile time.
            gpsDateTimeUtc = gpsMillis?.let { Instant.ofEpochMilli(it) },
        )
    }

    /** `<make>|<model>|<serial>`, lowercased, internal whitespace collapsed, a missing
     * part written as `-` — `design.md`'s exact recipe, kept here since it's derived
     * from the same three EXIF tags [extract] just read. */
    fun deviceKey(
        make: String?,
        model: String?,
        serial: String?,
    ): String = listOf(make, model, serial).joinToString("|") { normalise(it) }

    private fun normalise(part: String?): String {
        if (part.isNullOrBlank()) return "-"
        return part.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun ExifInterface.attribute(tag: String): String? = getAttribute(tag)?.trim()?.ifEmpty { null }

    /** Cameras populate `PixelXDimension`/`PixelYDimension` (the Exif SubIFD tags) far
     * more reliably than the TIFF-era `ImageWidth`/`ImageLength`; try the former first
     * and fall back to the latter, which `ExifInterface` derives itself from a JPEG's
     * SOF marker when nothing else supplies it. */
    private fun positiveDimension(
        exif: ExifInterface,
        primary: String,
        fallback: String,
    ): Int? {
        exif.getAttributeInt(primary, 0).takeIf { it > 0 }?.let { return it }
        return exif.getAttributeInt(fallback, 0).takeIf { it > 0 }
    }

    private val extensionMime =
        mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "gif" to "image/gif",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "dng" to "image/x-adobe-dng",
            "cr2" to "image/x-canon-cr2",
            "cr3" to "image/x-canon-cr3",
            "arw" to "image/x-sony-arw",
            "nef" to "image/x-nikon-nef",
            "orf" to "image/x-olympus-orf",
            "rw2" to "image/x-panasonic-rw2",
            "xmp" to "application/rdf+xml",
        )

    /** `ExifInterface` has no public MIME getter (its own format-sniffing is
     * package-private), so this is extension-based rather than content-sniffed —
     * good enough given the client already chose which files to queue by extension in
     * the first place ([fr.enry.archivist.sync.MediaStoreSource]). Unknown extensions
     * return null rather than guessing. */
    fun mimeFromDisplayName(displayName: String): String? =
        extensionMime[displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()]
}
