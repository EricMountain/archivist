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
 *
 * Version 5 added `sync_state.skipBeforeEpochSec` and *does* have a real migration
 * ([MIGRATION_4_5]): unlike the earlier bumps, dropping tables here would wipe
 * `upload_queue`, which is the only record of what was already seen — and a scanner that
 * has forgotten that re-queues the whole camera roll.
 *
 * Version 6 added `photos.preview` (the video preview clip's descriptor) with a real
 * migration ([MIGRATION_5_6]) that also empties the timeline cache — see its own doc.
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
        HistogramEntity::class,
        TimelineWindowEntity::class,
    ],
    version = 6,
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

    abstract fun histogramDao(): HistogramDao

    abstract fun timelineWindowDao(): TimelineWindowDao
}

val MIGRATION_4_5 =
    object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_state ADD COLUMN skipBeforeEpochSec INTEGER")
        }
    }

/**
 * Adds `photos.preview` and then **empties the timeline cache** (`photos`, `renditions`,
 * `timeline_cursor`, `timeline_window`). Not optional: [TimelineRemoteMediator] only
 * refreshes an *empty* cache, so rows cached before this column existed would carry
 * `preview = NULL` forever and no already-uploaded video would ever autoplay. It's
 * only a cache — everything in it is re-fetched from the server on the next launch.
 * Deliberately leaves `upload_queue` and `sync_state` alone (see [MIGRATION_4_5]).
 * `renditions` goes first because it references `photos`.
 */
val MIGRATION_5_6 =
    object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE photos ADD COLUMN preview TEXT")
            db.execSQL("DELETE FROM renditions")
            db.execSQL("DELETE FROM photos")
            db.execSQL("DELETE FROM timeline_cursor")
            db.execSQL("DELETE FROM timeline_window")
        }
    }
