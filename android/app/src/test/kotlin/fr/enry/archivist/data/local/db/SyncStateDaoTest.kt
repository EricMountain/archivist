package fr.enry.archivist.data.local.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncStateDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var folders: FolderSelectionDao
    private lateinit var cursor: TimelineCursorDao

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        folders = db.folderSelectionDao()
        cursor = db.timelineCursorDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `selecting a folder queues it enabled`() =
        runTest {
            folders.upsert(
                FolderSelectionEntity(
                    folderUri = "content://tree/camera",
                    displayName = "Camera",
                    enabled = true,
                    addedAt = "2026-08-30T10:00:00.000Z",
                ),
            )

            val all = folders.observeAll().first()

            assertTrue(all.single().enabled)
        }

    @Test
    fun `deselecting a folder disables it without deleting the row`() =
        runTest {
            folders.upsert(
                FolderSelectionEntity(
                    folderUri = "content://tree/camera",
                    displayName = "Camera",
                    enabled = true,
                    addedAt = "2026-08-30T10:00:00.000Z",
                ),
            )

            folders.setEnabled("content://tree/camera", false)

            val all = folders.observeAll().first()
            assertEquals(1, all.size)
            assertFalse(all.single().enabled)
        }

    @Test
    fun `no cursor stored yet observes as null`() =
        runTest {
            assertNull(cursor.observe().first())
        }

    @Test
    fun `setting the cursor twice replaces it, not appends`() =
        runTest {
            cursor.set(TimelineCursorEntity(cursor = "page1", updatedAt = "2026-08-30T10:00:00.000Z"))
            cursor.set(TimelineCursorEntity(cursor = "page2", updatedAt = "2026-08-30T10:05:00.000Z"))

            assertEquals("page2", cursor.observe().first()?.cursor)
        }
}
