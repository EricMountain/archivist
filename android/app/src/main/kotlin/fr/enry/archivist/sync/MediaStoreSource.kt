package fr.enry.archivist.sync

import java.io.InputStream

/** One MediaStore "bucket" — a folder-shaped grouping of images/video (Camera,
 * Screenshots, WhatsApp Images, …), identified by [bucketId]
 * (`MediaStore.Files.FileColumns.BUCKET_ID`), not a filesystem path. Plan step 2.7
 * ("List device folders via MediaStore") works at this granularity because MediaStore
 * itself has no folder-tree concept below it — this is a flat file index grouped by a
 * derived bucket, not a SAF document tree. */
data class DeviceFolder(
    val bucketId: String,
    val displayName: String,
    val itemCount: Int,
)

/** One image or video MediaStore already knows about, inside a selected folder. */
data class DeviceMediaFile(
    val contentUri: String,
    val displayName: String,
    val bucketId: String,
    val size: Long,
    val dateModified: Long,
)

/**
 * The one seam between [Scanner]/the folder-selection UI and the real
 * `ContentResolver` — a fake stands in for tests, the same role
 * [fr.enry.archivist.crypto.DeviceKeyProvider] plays for `AndroidKeyStore`: nothing in
 * this JVM test environment has a real one to query.
 */
interface MediaStoreSource {
    suspend fun listFolders(): List<DeviceFolder>

    suspend fun listFiles(bucketId: String): List<DeviceMediaFile>

    /** Caller closes it. [Scanner] reads the whole file exactly once, computing the
     * content hash from this same stream — see its class doc. */
    fun openInputStream(contentUri: String): InputStream
}
