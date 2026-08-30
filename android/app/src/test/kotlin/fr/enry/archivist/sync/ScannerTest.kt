package fr.enry.archivist.sync

import fr.enry.archivist.crypto.ContentHash
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.FolderSelectionEntity
import fr.enry.archivist.data.local.db.LocalTombstoneEntity
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.RenditionEntity
import fr.enry.archivist.data.local.db.RenditionRole
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.local.db.buildTestDatabase
import fr.enry.archivist.data.repo.HashSecretHolder
import fr.enry.archivist.testutil.FakeMediaStoreSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScannerTest {
    private lateinit var db: AppDatabase
    private lateinit var mediaStoreSource: FakeMediaStoreSource
    private lateinit var hashSecretHolder: HashSecretHolder
    private lateinit var scanner: Scanner

    private val hashSecret = ByteArray(32) { it.toByte() }

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        mediaStoreSource = FakeMediaStoreSource()
        hashSecretHolder = HashSecretHolder()
        hashSecretHolder.set(hashSecret)
        scanner =
            Scanner(
                mediaStoreSource = mediaStoreSource,
                folderSelectionDao = db.folderSelectionDao(),
                uploadQueueDao = db.uploadQueueDao(),
                renditionDao = db.renditionDao(),
                localTombstoneDao = db.localTombstoneDao(),
                hashSecretHolder = hashSecretHolder,
            )
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private suspend fun selectFolder(
        bucketId: String,
        name: String,
    ) {
        db.folderSelectionDao().upsert(
            FolderSelectionEntity(
                folderUri = bucketId,
                displayName = name,
                enabled = true,
                addedAt = "2026-08-30T10:00:00.000Z",
            ),
        )
    }

    @Test
    fun `fails cleanly with no hash secret available`() =
        runTest {
            val bareScanner =
                Scanner(
                    mediaStoreSource = mediaStoreSource,
                    folderSelectionDao = db.folderSelectionDao(),
                    uploadQueueDao = db.uploadQueueDao(),
                    renditionDao = db.renditionDao(),
                    localTombstoneDao = db.localTombstoneDao(),
                    hashSecretHolder = HashSecretHolder(),
                )

            val result = bareScanner.scan()

            assertTrue(result.isFailure)
        }

    @Test
    fun `selecting a folder queues its unsynced files`() =
        runTest {
            selectFolder("camera", "Camera")
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1, 2, 3))
            mediaStoreSource.addFile("camera", "Camera", "content://media/2", "IMG_2.jpg", byteArrayOf(4, 5, 6))

            val queued = scanner.scan().getOrThrow()

            assertEquals(2, queued)
            val rows = db.uploadQueueDao().observeAll().first()
            assertEquals(setOf("content://media/1", "content://media/2"), rows.map { it.localUri }.toSet())
            assertTrue(rows.all { it.state == UploadState.PENDING })
        }

    @Test
    fun `an unselected folder's files are never queued`() =
        runTest {
            mediaStoreSource.addFile("screenshots", "Screenshots", "content://media/1", "shot.png", byteArrayOf(9))
            // Deliberately not calling selectFolder — nothing is enabled.

            val queued = scanner.scan().getOrThrow()

            assertEquals(0, queued)
        }

    @Test
    fun `deselecting a folder stops future uploads without touching what's already queued`() =
        runTest {
            selectFolder("camera", "Camera")
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1))
            scanner.scan()
            val queuedBefore = db.uploadQueueDao().observeAll().first()
            assertEquals(1, queuedBefore.size)

            db.folderSelectionDao().setEnabled("camera", false)
            mediaStoreSource.addFile("camera", "Camera", "content://media/2", "IMG_2.jpg", byteArrayOf(2))
            scanner.scan()

            val queuedAfter = db.uploadQueueDao().observeAll().first()
            // Still just the one from before -- the new file was never even looked at,
            // and the original row is untouched.
            assertEquals(listOf("content://media/1"), queuedAfter.map { it.localUri })
        }

    @Test
    fun `a tombstoned file is never queued as PENDING`() =
        runTest {
            selectFolder("camera", "Camera")
            val content = byteArrayOf(7, 7, 7)
            val hash = ContentHash.of(hashSecret, content.inputStream())
            db.localTombstoneDao().upsert(LocalTombstoneEntity(contentHash = hash, deletedAt = "2026-08-30T10:00:00.000Z"))
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", content)

            val queued = scanner.scan().getOrThrow()

            assertEquals(0, queued)
            val row = db.uploadQueueDao().getByLocalUri("content://media/1")
            assertEquals(UploadState.DONE, row?.state)
        }

    @Test
    fun `a file already backed up under a different hash record is not re-queued`() =
        runTest {
            selectFolder("camera", "Camera")
            val content = byteArrayOf(5, 5, 5)
            val hash = ContentHash.of(hashSecret, content.inputStream())
            db.photoDao().upsertAll(
                listOf(
                    PhotoEntity(
                        photoId = "p1",
                        takenAt = "2026-08-30T10:00:00.000Z",
                        tzOffsetMin = 0,
                        mime = "image/jpeg",
                        width = 100,
                        height = 100,
                        status = AssetStatus.READY,
                        thumbs = emptyMap(),
                        encDek = "dek",
                        encKeyId = "mk-1",
                    ),
                ),
            )
            db.renditionDao().upsertAll(
                listOf(
                    RenditionEntity(
                        renditionId = "r1",
                        photoId = "p1",
                        role = RenditionRole.DISPLAY,
                        path = "already/uploaded.jpg",
                        ext = "jpg",
                        mime = "image/jpeg",
                        contentHash = hash,
                        bytes = content.size.toLong(),
                        plainBytes = content.size.toLong(),
                    ),
                ),
            )
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", content)

            val queued = scanner.scan().getOrThrow()

            assertEquals(0, queued)
        }

    @Test
    fun `re-scanning never re-hashes a file already seen`() =
        runTest {
            selectFolder("camera", "Camera")
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(1))

            scanner.scan()
            // Rig it so a second read would return different (wrong) bytes -- if the
            // scanner re-hashes, this test would see a duplicate/second row or a
            // different hash; if it correctly skips already-seen URIs, nothing changes.
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", byteArrayOf(9, 9, 9))
            val secondQueued = scanner.scan().getOrThrow()

            assertEquals(0, secondQueued)
            val rows = db.uploadQueueDao().observeAll().first()
            assertEquals(1, rows.size)
        }

    @Test
    fun `two identical files in different folders are queued once, not rejected`() =
        runTest {
            selectFolder("camera", "Camera")
            selectFolder("whatsapp", "WhatsApp Images")
            val content = byteArrayOf(3, 3, 3)
            mediaStoreSource.addFile("camera", "Camera", "content://media/1", "IMG_1.jpg", content)
            mediaStoreSource.addFile("whatsapp", "WhatsApp Images", "content://media/2", "IMG_1.jpg", content)

            val queued = scanner.scan().getOrThrow()

            // The point of insertIfNewContent's IGNORE is what's actually asserted:
            // this doesn't throw and doesn't queue the same content twice. The second
            // URI's own row is a casualty of IGNORE dropping the whole row on *any*
            // unique-constraint hit, not just contentHash's -- so content://media/2
            // isn't remembered by getByLocalUri either, and a future scan will re-hash
            // it (and again find it's a duplicate). Acceptable: correctness (never
            // double-queues real content) over a perfect cache for this specific,
            // uncommon case.
            assertEquals(1, queued)
            val rows = db.uploadQueueDao().observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("content://media/1", rows.single().localUri)
        }

    @Test
    fun `a folder with nothing selected leaves the queue untouched, no crash`() =
        runTest {
            val queued = scanner.scan().getOrThrow()

            assertEquals(0, queued)
            assertNull(db.uploadQueueDao().getByContentHash("anything"))
        }
}
