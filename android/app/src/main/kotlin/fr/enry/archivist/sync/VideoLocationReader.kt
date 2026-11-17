package fr.enry.archivist.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A video's recorded location, as the raw ISO 6709 string the container carries
 * (`+35.0116+135.7681/`); [fr.enry.archivist.domain.Iso6709] parses it. Videos have no
 * EXIF, so this is the video half of what [fr.enry.archivist.domain.ExifExtractor] does for
 * stills (geolocation plan step 5). An interface so JVM tests can stand in for
 * `MediaMetadataRetriever`, which has no JVM implementation.
 */
interface VideoLocationReader {
    /**
     * Reads [strippedFile] when it is non-null (the location-stripped copy plan step 2.18
     * made, which then has no location by construction), else [contentUri]. Null when the
     * container has no location or can't be read: a best-effort read never fails an upload.
     */
    suspend fun read(
        contentUri: String,
        strippedFile: File?,
    ): String?
}

/**
 * `MediaMetadataRetriever.METADATA_KEY_LOCATION`, which covers QuickTime's `©xyz` and the
 * `moov/meta` keys+ilst location entry. The MediaStore source is opened through
 * [originalIfPermitted], the same rule as a still's EXIF read: without
 * `ACCESS_MEDIA_LOCATION` the platform redacts the location and this returns null.
 */
class AndroidVideoLocationReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : VideoLocationReader {
        /** [MediaMetadataRetriever.release], not `.use {}`: it's only been
         * [AutoCloseable] since API 29, below this app's minSdk 28. */
        override suspend fun read(
            contentUri: String,
            strippedFile: File?,
        ): String? =
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (strippedFile != null) {
                        retriever.setDataSource(strippedFile.absolutePath)
                    } else {
                        val uri = originalIfPermitted(context, Uri.parse(contentUri))
                        // Safe to close once setDataSource returns (its own documentation).
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) }
                            ?: return@withContext null
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                } catch (e: Exception) {
                    // setDataSource throws IllegalArgumentException/RuntimeException, not
                    // IOException, for an unreadable source (android/AGENTS.md).
                    Log.w("VideoLocationReader", "could not read a video's location", e)
                    null
                } finally {
                    retriever.release()
                }
            }
    }
