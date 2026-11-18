package fr.enry.archivist.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
 *
 * Version 7 added `photos.placeCc`/`placeName`/`placeKey` (geolocation plan step 6), the
 * same way ([MIGRATION_6_7]).
 *
 * Version 8 added the `place_vocabulary` table (geolocation plan step 8): the cached
 * `GET /places` answer, see [PlaceVocabularyEntity]. A plain `CREATE TABLE`
 * ([MIGRATION_7_8]); nothing existing changes.
 *
 * Version 9 added `facet_streams` and `facet_stream_rows` (timeline place filter step 2):
 * a client-only session cache of per-place facet pages, see [FacetStreamEntity]. Plain
 * `CREATE TABLE`s ([MIGRATION_8_9]); nothing existing changes.
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
        PlaceVocabularyEntity::class,
        FacetStreamEntity::class,
        FacetStreamRowEntity::class,
    ],
    version = 9,
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

    abstract fun placeVocabularyDao(): PlaceVocabularyDao

    abstract fun facetStreamDao(): FacetStreamDao
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

/**
 * Adds `photos.placeCc`/`placeName`/`placeKey` and empties the timeline cache, for the
 * same reason as [MIGRATION_5_6]: [TimelineRemoteMediator] only refreshes an *empty*
 * cache, so rows cached before this would show no place in the date bubbles until each
 * happened to be re-fetched. Leaves `upload_queue` and `sync_state` alone.
 *
 * Written against [SQLiteConnection] rather than `SupportSQLiteDatabase` so the JVM
 * migration test (bundled driver) and the app (framework SQLite, which Room hands over
 * as a connection wrapper) run the same code.
 */
val MIGRATION_6_7 =
    object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE photos ADD COLUMN placeCc TEXT")
            connection.execSQL("ALTER TABLE photos ADD COLUMN placeName TEXT")
            connection.execSQL("ALTER TABLE photos ADD COLUMN placeKey TEXT")
            connection.execSQL("DELETE FROM renditions")
            connection.execSQL("DELETE FROM photos")
            connection.execSQL("DELETE FROM timeline_cursor")
            connection.execSQL("DELETE FROM timeline_window")
        }
    }

/**
 * Adds `place_vocabulary` (see [PlaceVocabularyEntity]). Touches nothing else: the new
 * cache starts empty and the search screen fills it on first open. The `CREATE TABLE` is
 * Room's own `createSql` from `schemas/.../8.json`, so the post-migration schema check
 * passes. Connection form for the same reason as [MIGRATION_6_7].
 */
val MIGRATION_7_8 =
    object : androidx.room.migration.Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `place_vocabulary` " +
                    "(`id` INTEGER NOT NULL, `body` TEXT NOT NULL, `etag` TEXT, PRIMARY KEY(`id`))",
            )
        }
    }

/**
 * Adds the place filter's session cache, `facet_streams` and `facet_stream_rows` (see
 * [FacetStreamEntity]), empty. Touches nothing else. The statements are Room's own
 * `createSql` from `schemas/.../9.json`, so the post-migration schema check passes.
 * Connection form for the same reason as [MIGRATION_6_7].
 */
val MIGRATION_8_9 =
    object : androidx.room.migration.Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `facet_streams` (`streamKey` TEXT NOT NULL, `cursor` TEXT, " +
                    "`oldestTakenAt` TEXT, `oldestPhotoId` TEXT, `done` INTEGER NOT NULL, `fetchedAt` TEXT NOT NULL, " +
                    "PRIMARY KEY(`streamKey`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `facet_stream_rows` (`streamKey` TEXT NOT NULL, `photoId` TEXT NOT NULL, " +
                    "`takenAt` TEXT NOT NULL, `tzOffsetMin` INTEGER NOT NULL, `mime` TEXT NOT NULL, `width` INTEGER NOT NULL, " +
                    "`height` INTEGER NOT NULL, `status` TEXT NOT NULL, `thumbs` TEXT NOT NULL, `encDek` TEXT NOT NULL, " +
                    "`encKeyId` TEXT NOT NULL, `placeCc` TEXT, `placeName` TEXT, `placeKey` TEXT, " +
                    "`specificity` INTEGER NOT NULL, `localDay` TEXT NOT NULL, PRIMARY KEY(`streamKey`, `photoId`))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_facet_stream_rows_photoId` ON `facet_stream_rows` (`photoId`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_facet_stream_rows_streamKey_takenAt` " +
                    "ON `facet_stream_rows` (`streamKey`, `takenAt`)",
            )
        }
    }
