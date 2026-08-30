package fr.enry.archivist.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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

        private inline fun query(
            projection: Array<String>,
            selection: String,
            selectionArgs: Array<String>,
            block: (android.database.Cursor) -> Unit,
        ) {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use(block)
        }
    }
