package fr.enry.archivist.testutil

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** One image this fixture inserted into the real on-device `MediaStore` — everything a
 * caller needs to both drive [fr.enry.archivist.sync.MediaStoreSource] against it and
 * clean it up afterward. */
data class InsertedMedia(val uri: Uri, val contentUri: String, val bucketId: String, val plainBytes: Int)

/**
 * Real image bytes for instrumented tests to insert into the real on-device
 * `MediaStore` — no JVM fake here, since the whole point of an instrumented test is
 * exercising the real `ContentResolver`/`MediaStore` a JVM test can't. `javax.imageio`
 * (used for JVM-only fixtures elsewhere, e.g. `ExifExtractorTest`) doesn't exist on
 * Android at all; `android.graphics.Bitmap` is the real platform equivalent.
 */
object MediaStoreFixtures {
    /** Inserts a solid-colour JPEG under `Pictures/ArchivistTest` and reads back the
     * `BUCKET_ID` `MediaStore` derived for it — [fr.enry.archivist.sync.MediaStoreSource.listFiles]
     * is keyed by that, not by path, so a caller can't just compute it from the
     * `RELATIVE_PATH` it requested. */
    fun insertJpeg(
        context: Context,
        displayName: String,
        width: Int = 64,
        height: Int = 48,
    ): InsertedMedia {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArchivistTest")
            }
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed for $displayName")

        var byteCount = 0
        resolver.openOutputStream(uri)?.use { out ->
            val bytes =
                java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            byteCount = bytes.size
            out.write(bytes)
        } ?: error("no output stream for $uri")
        bitmap.recycle()

        val id = ContentUris.parseId(uri)
        val bucketId =
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.BUCKET_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) error("inserted row $id vanished before it could be read back")
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID))
            } ?: error("query against $uri returned nothing")

        // AndroidMediaStoreSource queries/builds URIs via MediaStore.Files (the
        // generic collection every media type shares), not the images-specific
        // collection this insert used -- both name the same underlying row, but as
        // different URI strings, and Scanner/UploadQueueDao match rows by that exact
        // string. Recompute it the same way AndroidMediaStoreSource does, or a
        // caller's getByLocalUri(media.contentUri) never finds what Scanner queued.
        val filesUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

        return InsertedMedia(uri, filesUri.toString(), bucketId, byteCount)
    }

    fun delete(
        context: Context,
        media: InsertedMedia,
    ) {
        context.contentResolver.delete(media.uri, null, null)
    }
}
