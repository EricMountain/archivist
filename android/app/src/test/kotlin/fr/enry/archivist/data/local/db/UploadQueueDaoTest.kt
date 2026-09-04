package fr.enry.archivist.data.local.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UploadQueueDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: UploadQueueDao

    private fun entry(
        localUri: String,
        contentHash: String? = null,
        state: UploadState = UploadState.PENDING,
        createdAt: String = "2026-08-30T10:00:00.000Z",
    ) = UploadQueueEntity(
        localUri = localUri,
        displayName = localUri.substringAfterLast('/'),
        folderUri = "content://media/external/images/media",
        contentHash = contentHash,
        state = state,
        plainBytes = null,
        fileMtimeEpochSec = null,
        takenAt = null,
        tzOffsetMin = null,
        takenAtSrc = null,
        tzSrc = null,
        mime = null,
        width = null,
        height = null,
        photoId = null,
        renditionId = null,
        attempts = 0,
        lastError = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        dao = db.uploadQueueDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a new file is queued as PENDING`() =
        runTest {
            dao.insert(entry("content://media/1"))

            val all = dao.observeAll().first()

            assertEquals(1, all.size)
            assertEquals(UploadState.PENDING, all.single().state)
        }

    @Test
    fun `advancing state updates the same row rather than inserting a new one`() =
        runTest {
            val id = dao.insert(entry("content://media/1"))

            dao.update(entry("content://media/1").copy(id = id, state = UploadState.UPLOADING, contentHash = "hash1"))

            val all = dao.observeAll().first()
            assertEquals(1, all.size)
            assertEquals(UploadState.UPLOADING, all.single().state)
            assertEquals("hash1", all.single().contentHash)
        }

    @Test
    fun `observePending excludes DONE rows by default`() =
        runTest {
            dao.insert(entry("content://media/1", state = UploadState.PENDING))
            dao.insert(entry("content://media/2", state = UploadState.DONE))

            val pending = dao.observePending().first()

            assertEquals(listOf("content://media/1"), pending.map { it.localUri })
        }

    @Test
    fun `getByContentHash finds a queued duplicate before it's re-queued`() =
        runTest {
            dao.insert(entry("content://media/1", contentHash = "hash1"))

            assertEquals("content://media/1", dao.getByContentHash("hash1")?.localUri)
            assertNull(dao.getByContentHash("hash-never-seen"))
        }

    @Test
    fun `getByPhotoId finds every local rendition uploaded under one server asset`() =
        runTest {
            dao.insert(entry("content://media/1", contentHash = "hash-jpg").copy(photoId = "p1", renditionId = "r1"))
            dao.insert(entry("content://media/2", contentHash = "hash-raw").copy(photoId = "p1", renditionId = "r2"))
            dao.insert(entry("content://media/3", contentHash = "hash-other").copy(photoId = "p2", renditionId = "r3"))

            val rows = dao.getByPhotoId("p1")

            assertEquals(setOf("content://media/1", "content://media/2"), rows.map { it.localUri }.toSet())
        }

    @Test
    fun `resetForRetry clears a failed row back to PENDING with no lingering error`() =
        runTest {
            val id =
                dao.insert(entry("content://media/1", state = UploadState.FAILED).copy(attempts = 2, lastError = "server rejected upload"))

            dao.resetForRetry(id, "2026-09-05T00:00:00.000Z")

            val row = dao.getById(id)
            assertEquals(UploadState.PENDING, row?.state)
            assertNull(row?.lastError)
            assertEquals(2, row?.attempts)
        }

    @Test
    fun `deleteByState clears finished rows without touching the rest`() =
        runTest {
            dao.insert(entry("content://media/1", state = UploadState.DONE))
            dao.insert(entry("content://media/2", state = UploadState.PENDING))

            dao.deleteByState(UploadState.DONE)

            val all = dao.observeAll().first()
            assertEquals(listOf("content://media/2"), all.map { it.localUri })
        }
}
