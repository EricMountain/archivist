package fr.enry.archivist.testutil

import fr.enry.archivist.sync.DeviceFolder
import fr.enry.archivist.sync.DeviceMediaFile
import fr.enry.archivist.sync.MediaStoreSource
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Stands in for [fr.enry.archivist.sync.AndroidMediaStoreSource] — no JVM unit test
 * environment has a real `ContentResolver` to query. */
class FakeMediaStoreSource : MediaStoreSource {
    private val folders = mutableListOf<DeviceFolder>()
    private val filesByBucket = mutableMapOf<String, MutableList<DeviceMediaFile>>()
    private val contentByUri = mutableMapOf<String, ByteArray>()

    fun addFile(
        bucketId: String,
        bucketName: String,
        contentUri: String,
        displayName: String,
        content: ByteArray,
    ) {
        val file = DeviceMediaFile(contentUri, displayName, bucketId, content.size.toLong(), 0L)
        filesByBucket.getOrPut(bucketId) { mutableListOf() }.add(file)
        contentByUri[contentUri] = content
        val existingIndex = folders.indexOfFirst { it.bucketId == bucketId }
        if (existingIndex == -1) {
            folders.add(DeviceFolder(bucketId, bucketName, 1))
        } else {
            folders[existingIndex] = folders[existingIndex].copy(itemCount = folders[existingIndex].itemCount + 1)
        }
    }

    override suspend fun listFolders(): List<DeviceFolder> = folders.toList()

    override suspend fun listFiles(bucketId: String): List<DeviceMediaFile> = filesByBucket[bucketId]?.toList() ?: emptyList()

    override fun openInputStream(contentUri: String): InputStream =
        ByteArrayInputStream(contentByUri[contentUri] ?: error("no fake content for $contentUri"))
}
