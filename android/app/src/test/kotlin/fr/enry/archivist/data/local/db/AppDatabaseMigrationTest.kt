package fr.enry.archivist.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Real Room migrations, as a plain JVM test (bundled SQLite, no device; see
 * `android/AGENTS.md`). The old version's tables are created from Room's own exported
 * schema JSON (`app/schemas/`), the file a migration is diffed against, and then
 * [AppDatabase] is opened on that file with only the migration under test registered and
 * no destructive fallback, so Room's own post-migration schema validation (every table
 * against the entities) is the main assertion.
 */
class AppDatabaseMigrationTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("migration").toFile()
        file = File(dir, "archivist.db")
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Creates [file] at [version] from `<version>.json`, then runs [seed] on it. */
    private fun createAt(
        version: Int,
        seed: SQLiteConnection.() -> Unit,
    ) {
        val schemas = File(System.getProperty("archivist.room.schemas") ?: error("archivist.room.schemas not set"))
        val database = Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject.getValue("database").jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            for (entity in database.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                val sqls = listOf(e.getValue("createSql")) + (e["indices"]?.jsonArray ?: emptyList()).map { it.jsonObject.getValue("createSql") }
                for (sql in sqls) connection.execSQL(sql.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            for (sql in database.getValue("setupQueries").jsonArray) connection.execSQL(sql.jsonPrimitive.content)
            connection.execSQL("PRAGMA user_version = $version")
            connection.seed()
        } finally {
            connection.close()
        }
    }

    private fun open(vararg migrations: androidx.room.migration.Migration): AppDatabase {
        val context = mock<Context> { on { getDatabasePath(any()) } doReturn file }
        return Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun AppDatabase.count(table: String): Long =
        useReaderConnection { c -> c.usePrepared("SELECT count(*) FROM $table") { it.step(); it.getLong(0) } }

    @Test
    fun `6 to 7 adds the place columns and empties only the timeline cache`() =
        runTest {
            createAt(6) {
                execSQL(
                    "INSERT INTO photos (photoId, takenAt, tzOffsetMin, mime, width, height, status, thumbs, encDek, encKeyId, preview) " +
                        "VALUES ('p1', '2026-07-14T03:00:00.000Z', 540, 'image/jpeg', 1, 1, 'READY', '{}', 'd', 'k', NULL)",
                )
                execSQL(
                    "INSERT INTO renditions (renditionId, photoId, role, path, ext, mime, contentHash, bytes, plainBytes) " +
                        "VALUES ('r1', 'p1', 'PRIMARY', 'a.jpg', 'jpg', 'image/jpeg', 'h1', 1, 1)",
                )
                execSQL("INSERT INTO timeline_cursor (id, cursor, updatedAt) VALUES (0, 'c', '2026-07-14T03:00:00.000Z')")
                execSQL("INSERT INTO timeline_window (id, completeThrough) VALUES (0, NULL)")
                execSQL(
                    "INSERT INTO upload_queue (localUri, displayName, folderUri, state, attempts, createdAt, updatedAt) " +
                        "VALUES ('content://x/1', 'a.jpg', 'f', 'DONE', 0, 't', 't')",
                )
                execSQL("INSERT INTO sync_state (folderUri, displayName, enabled, addedAt, skipBeforeEpochSec) VALUES ('f', 'Camera', 1, 't', 5)")
            }

            // Opening validates against the current version, so the chain runs on to it.
            val db = open(MIGRATION_6_7, MIGRATION_7_8)
            try {
                // The cache is emptied so the mediator's cold-start refresh re-fetches
                // every row with its place (see MIGRATION_6_7's doc).
                assertTrue(db.photoDao().isEmpty())
                assertEquals(0L, db.count("renditions"))
                assertEquals(0L, db.count("timeline_cursor"))
                assertEquals(0L, db.count("timeline_window"))
                // What the scanner has already seen survives.
                assertEquals(1L, db.count("upload_queue"))
                assertEquals(1L, db.count("sync_state"))

                val photo =
                    PhotoEntity(
                        photoId = "p2",
                        takenAt = "2026-07-14T03:00:00.000Z",
                        tzOffsetMin = 540,
                        mime = "image/jpeg",
                        width = 1,
                        height = 1,
                        status = AssetStatus.READY,
                        thumbs = emptyMap(),
                        encDek = "d",
                        encKeyId = "k",
                        placeCc = "JP",
                        placeName = "Kyoto",
                        placeKey = "1857910",
                    )
                db.photoDao().upsertAll(listOf(photo))
                assertEquals(photo, db.photoDao().getByPhotoId("p2"))
            } finally {
                db.close()
            }
        }

    @Test
    fun `7 to 8 adds an empty place vocabulary cache and keeps everything else`() =
        runTest {
            createAt(7) {
                execSQL(
                    "INSERT INTO photos (photoId, takenAt, tzOffsetMin, mime, width, height, status, thumbs, encDek, encKeyId, preview, placeCc, placeName, placeKey) " +
                        "VALUES ('p1', '2026-07-14T03:00:00.000Z', 540, 'image/jpeg', 1, 1, 'READY', '{}', 'd', 'k', NULL, 'JP', 'Kyoto', '1857910')",
                )
                execSQL("INSERT INTO timeline_histogram (id, days, total, etag) VALUES (0, '{}', 0, '\"tl-1\"')")
            }

            val db = open(MIGRATION_7_8)
            try {
                assertEquals("Kyoto", db.photoDao().getByPhotoId("p1")?.placeName)
                assertEquals("\"tl-1\"", db.histogramDao().get()?.etag)
                assertEquals(null, db.placeVocabularyDao().get())
                db.placeVocabularyDao().set("{}", "\"pl-1\"")
                assertEquals(PlaceVocabularyEntity(body = "{}", etag = "\"pl-1\""), db.placeVocabularyDao().get())
            } finally {
                db.close()
            }
        }
}
