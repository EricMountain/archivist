package fr.enry.archivist.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.FolderSelectionEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.testutil.InsertedMedia
import fr.enry.archivist.testutil.MediaStoreFixtures
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan step 2.10: [Scanner] against a *real* `MediaStore`/`ContentResolver`
 * ([AndroidMediaStoreSource]) and a real on-device Room database — the JVM-level
 * `ScannerTest` already covers the scan logic itself against a fake source, so this is
 * specifically about the one seam a JVM test can't reach.
 *
 * No Hilt needed: every collaborator [Scanner] takes is either plain Kotlin
 * ([HashSecretHolder]) or constructible directly from a real [android.content.Context]
 * (`AndroidMediaStoreSource`, a throwaway Room database) — see `TestEntryPoint.kt`'s doc
 * for why the heavier instrumented tests in this module still avoid a full Hilt test
 * setup even where they *do* need Hilt-provided singletons.
 */
@RunWith(AndroidJUnit4::class)
class ScannerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var scanner: Scanner
    private val hashSecret = ByteArray(32) { it.toByte() }
    private val inserted = mutableListOf<InsertedMedia>()

    @Before
    fun setUp() {
        db =
            Room.databaseBuilder(context, AppDatabase::class.java, "scanner-instrumented-test.db")
                .fallbackToDestructiveMigration(true)
                .build()
        scanner =
            Scanner(
                mediaStoreSource = AndroidMediaStoreSource(context),
                folderSelectionDao = db.folderSelectionDao(),
                uploadQueueDao = db.uploadQueueDao(),
                renditionDao = db.renditionDao(),
                localTombstoneDao = db.localTombstoneDao(),
                hashSecretHolder = HashSecretHolder().apply { set(hashSecret) },
            )
    }

    @After
    fun tearDown() {
        inserted.forEach { MediaStoreFixtures.delete(context, it) }
        db.close()
        context.deleteDatabase("scanner-instrumented-test.db")
    }

    @Test
    fun scanningARealSelectedFolderQueuesARealMediaStoreFile() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "scanner_instrumented_${System.nanoTime()}.jpg")
            inserted += media

            db.folderSelectionDao().upsert(
                FolderSelectionEntity(
                    folderUri = media.bucketId,
                    displayName = "ArchivistTest",
                    enabled = true,
                    addedAt = Instant.now().toString(),
                ),
            )

            val queued = scanner.scan().getOrThrow()

            assertEquals(1, queued)
            val row = db.uploadQueueDao().getByLocalUri(media.contentUri)
            assertNotNull(row)
            assertEquals(UploadState.PENDING, row!!.state)
            assertNotNull(row.contentHash)
            // The real MediaStore SIZE column, not a fake's stand-in -- this is the
            // exact field plan step 2.10's worker reads as `plainBytes` in the POST body.
            assertEquals(media.plainBytes.toLong(), row.plainBytes)
        }

    @Test
    fun rescanningTheSameFileNeverRereadsOrRequeuesIt() =
        runBlocking {
            val media = MediaStoreFixtures.insertJpeg(context, "scanner_instrumented_rescan_${System.nanoTime()}.jpg")
            inserted += media
            db.folderSelectionDao().upsert(
                FolderSelectionEntity(media.bucketId, "ArchivistTest", enabled = true, addedAt = Instant.now().toString()),
            )

            val first = scanner.scan().getOrThrow()
            val second = scanner.scan().getOrThrow()

            assertEquals(1, first)
            assertEquals(0, second)
            assertEquals(1, db.uploadQueueDao().observeAll().first().size)
        }
}
