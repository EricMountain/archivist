package fr.enry.archivist.sync

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.sync.video.Mp4BoxEditor
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The full `TAG_GPS_*` set `androidx.exifinterface` exposes — nulling every one of
 * these off a copy is plan step 2.18's image-side mechanism (see "Mechanism, images"
 * in design.md). Listed explicitly rather than filtered by name prefix, since
 * `ExifInterface`'s tag constants aren't otherwise enumerable at runtime. */
private val GPS_TAGS =
    listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_VERSION_ID,
    )

/** Mimes `androidx.exifinterface` can rewrite in place — the same set
 * [fr.enry.archivist.domain.ExifExtractor.mimeFromDisplayName] already knows, reused
 * rather than duplicated. */
private val EXIF_STRIPPABLE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
private val VIDEO_STRIPPABLE_MIMES = setOf("video/mp4", "video/quicktime")

/**
 * Plan step 2.18 — see "Stripping location on upload" in design.md. Given the
 * [MediaStoreSource] URI a file would otherwise be read from and its mime, produces a
 * location-stripped *copy* under [Context.getCacheDir] and returns it, or `null` when
 * the mime is one this app doesn't know how to strip. The original is opened only for
 * reading here, never for writing — nothing in this class touches [contentUri] itself.
 * Callers own deleting the returned file once the upload attempt for it finishes.
 */
class LocationStripper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val mediaStoreSource: MediaStoreSource,
    ) {
        suspend fun strip(
            contentUri: String,
            mime: String,
        ): File? =
            withContext(Dispatchers.IO) {
                when (mime) {
                    in EXIF_STRIPPABLE_MIMES -> stripExif(contentUri)
                    in VIDEO_STRIPPABLE_MIMES -> stripVideo(contentUri)
                    else -> null
                }
            }

        private fun stripExif(contentUri: String): File {
            val copy = copyToCache(contentUri, "strip-exif-")
            val exif = ExifInterface(copy.path)
            for (tag in GPS_TAGS) exif.setAttribute(tag, null)
            exif.saveAttributes()
            return copy
        }

        private fun stripVideo(contentUri: String): File {
            val copy = copyToCache(contentUri, "strip-video-")
            Mp4BoxEditor.stripLocation(copy)
            return copy
        }

        /** `cacheDir`, not `noBackupFilesDir` — this file is deleted within the same
         * upload attempt, not a durable cache the way Coil's decrypted-thumbnail cache
         * is (see "Decrypting for display" in android.md). */
        private fun copyToCache(
            contentUri: String,
            prefix: String,
        ): File {
            val temp = File.createTempFile(prefix, ".tmp", context.cacheDir)
            mediaStoreSource.openInputStream(contentUri).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            return temp
        }
    }
