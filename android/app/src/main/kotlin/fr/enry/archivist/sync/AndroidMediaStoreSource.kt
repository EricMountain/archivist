package fr.enry.archivist.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [MediaStoreSource] backed by the real `ContentResolver`. Images and video only —
 * `MediaStore.Files` also indexes documents/audio, which this app never backs up. */
class AndroidMediaStoreSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MediaStoreSource {
        private val collection = MediaStore.Files.getContentUri("external")
        private val baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        private val baseSelectionArgs =
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )

        override suspend fun listFolders(): List<DeviceFolder> =
            withContext(Dispatchers.IO) {
                // LinkedHashMap: first-seen order is good enough, and stable across a
                // single query — no sort requirement from the plan.
                val buckets = LinkedHashMap<String, Pair<String, Int>>()
                query(
                    projection =
                        arrayOf(MediaStore.Files.FileColumns.BUCKET_ID, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME),
                    selection = baseSelection,
                    selectionArgs = baseSelectionArgs,
                ) { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val bucketId = cursor.getString(idCol) ?: continue
                        val bucketName = cursor.getString(nameCol) ?: bucketId
                        val existing = buckets[bucketId]
                        buckets[bucketId] = bucketName to ((existing?.second ?: 0) + 1)
                    }
                }
                buckets.map { (id, nameAndCount) -> DeviceFolder(id, nameAndCount.first, nameAndCount.second) }
            }

        override suspend fun listFiles(bucketId: String): List<DeviceMediaFile> =
            withContext(Dispatchers.IO) {
                val result = mutableListOf<DeviceMediaFile>()
                query(
                    projection =
                        arrayOf(
                            MediaStore.Files.FileColumns._ID,
                            MediaStore.Files.FileColumns.DISPLAY_NAME,
                            MediaStore.Files.FileColumns.SIZE,
                            MediaStore.Files.FileColumns.DATE_MODIFIED,
                        ),
                    selection = "$baseSelection AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?",
                    selectionArgs = baseSelectionArgs + bucketId,
                ) { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        result.add(
                            DeviceMediaFile(
                                contentUri = ContentUris.withAppendedId(collection, id).toString(),
                                displayName = cursor.getString(nameCol) ?: id.toString(),
                                bucketId = bucketId,
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(dateCol),
                            ),
                        )
                    }
                }
                result
            }

        override fun openInputStream(contentUri: String): InputStream =
            context.contentResolver.openInputStream(Uri.parse(contentUri))
                ?: throw FileNotFoundException(contentUri)

        /**
         * API 30+ (`MediaStore.createDeleteRequest`, added in R): always returns one
         * batched confirmation `PendingIntent` covering every URI at once, regardless of
         * whether this app owns the file — that always-confirm behavior is the
         * platform's own, not a choice made here. Below API 30: a plain delete per URI,
         * which only actually succeeds for a file this app itself owns (never true here
         * — these are the user's own camera-roll photos); anything else throws
         * `SecurityException` (on API 29 specifically, the recoverable subtype carrying
         * its own per-file confirmation `IntentSender` — deliberately not special-cased:
         * catching a class introduced after this app's `minSdk` 28 in a code path that
         * also runs *on* API 28 risks exactly the class-resolution crash this app can't
         * verify it's safe from, for the sake of one narrow, shrinking OS version).
         * Below API 30 the user instead sees a clean [Failed] and has to remove the file
         * from Gallery themselves — a deliberate, documented scope trim, not an oversight
         * (see plan step 2.13's STATUS.md row).
         *
         * **`toTypedMediaUri` is not optional** — found live, the hard way: passing a
         * `MediaStore.Files` URI (what [listFiles] builds and [UploadQueueEntity.localUri]
         * stores — see this class's own top-level doc) straight to
         * `MediaStore.createDeleteRequest` throws `IllegalArgumentException: All
         * requested items must be Media items` from deep inside the platform's own
         * `ContentProvider`, on a background dispatcher with nothing upstream catching
         * it — crashing the whole app, not just failing this one delete. Confirmed via a
         * real crash on a real emulator (API 37), not from documentation.
         */
        override suspend fun requestDelete(contentUris: List<String>): MediaDeleteOutcome =
            withContext(Dispatchers.IO) {
                if (contentUris.isEmpty()) return@withContext MediaDeleteOutcome.Deleted
                val uris = contentUris.mapNotNull { toTypedMediaUri(Uri.parse(it)) }
                if (uris.isEmpty()) return@withContext MediaDeleteOutcome.Deleted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    MediaDeleteOutcome.NeedsConfirmation(pendingIntent.intentSender)
                } else {
                    try {
                        uris.forEach { context.contentResolver.delete(it, null, null) }
                        MediaDeleteOutcome.Deleted
                    } catch (e: SecurityException) {
                        MediaDeleteOutcome.Failed(e.message ?: "permission denied — remove it from Gallery instead")
                    }
                }
            }

        /** Rebuilds a `MediaStore.Files` URI's numeric id under the type-specific
         * collection (`Images.Media`/`Video.Media`) [MediaStore.createDeleteRequest]
         * actually requires — see [requestDelete]'s own doc for why this exists at all.
         * `null` for a row that's already gone (no longer queryable) or isn't image/video
         * (shouldn't happen — [listFiles] only ever indexes those two — but a stale
         * `upload_queue` row from before some future media-type change is cheap
         * insurance); the caller treats a `null` as "nothing to delete for this one"
         * rather than failing the whole batch over it. */
        private fun toTypedMediaUri(filesUri: Uri): Uri? {
            val id = ContentUris.parseId(filesUri)
            val mediaType =
                context.contentResolver.query(
                    filesUri,
                    arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
                    } else {
                        null
                    }
                } ?: return null

            return when (mediaType) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                else -> null
            }
        }

        private inline fun query(
            projection: Array<String>,
            selection: String,
            selectionArgs: Array<String>,
            block: (android.database.Cursor) -> Unit,
        ) {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use(block)
        }
    }
