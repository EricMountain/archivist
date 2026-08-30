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
 * against; version 1 has no migration of its own yet, since nothing has shipped this
 * schema before.
 */
@Database(
    entities = [
        PhotoEntity::class,
        RenditionEntity::class,
        UploadQueueEntity::class,
        LocalTombstoneEntity::class,
        FolderSelectionEntity::class,
        TimelineCursorEntity::class,
    ],
    version = 1,
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
}
