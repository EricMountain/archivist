package fr.enry.archivist.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local storage plan step 2.6 asks for: a timeline cache and an upload queue that
 * both survive process death (android.md, "Architecture" and "Upload pipeline"). Room
 * is the source of truth the UI reads — nothing binds directly to a network response.
 *
 * `exportSchema = true` so a future schema change has something to diff a migration
 * against.
 *
 * Version 2 (plan step 2.10) added four `upload_queue` columns (`plainBytes`,
 * `fileMtimeEpochSec`, `takenAtSrc`, `tzSrc`) the upload worker needs. Version 3 (plan
 * step 2.14) added the `devices` table — a local cache of `GET /devices`, see
 * [DeviceEntity]'s own doc. No real migration for either bump —
 * [fr.enry.archivist.data.local.LocalStorageModule] falls back to a destructive one,
 * which only ever drops a local cache/queue (nothing server-side), and nothing has
 * shipped this schema to a real install yet.
 */
@Database(
    entities = [
        PhotoEntity::class,
        RenditionEntity::class,
        UploadQueueEntity::class,
        LocalTombstoneEntity::class,
        FolderSelectionEntity::class,
        TimelineCursorEntity::class,
        DeviceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    abstract fun renditionDao(): RenditionDao

    abstract fun uploadQueueDao(): UploadQueueDao

    abstract fun localTombstoneDao(): LocalTombstoneDao

    abstract fun folderSelectionDao(): FolderSelectionDao

    abstract fun timelineCursorDao(): TimelineCursorDao

    abstract fun deviceDao(): DeviceDao
}
