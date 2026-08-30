package fr.enry.archivist.data.local.db

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalTombstoneDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LocalTombstoneDao

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        dao = db.localTombstoneDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a tombstoned hash is skippable, an unseen one isn't`() =
        runTest {
            dao.upsert(LocalTombstoneEntity(contentHash = "hash1", deletedAt = "2026-08-30T10:00:00.000Z"))

            assertTrue(dao.exists("hash1"))
            assertFalse(dao.exists("hash2"))
        }

    @Test
    fun `deleting a tombstone lets the file be re-queued`() =
        runTest {
            dao.upsert(LocalTombstoneEntity(contentHash = "hash1", deletedAt = "2026-08-30T10:00:00.000Z"))

            dao.delete("hash1")

            assertFalse(dao.exists("hash1"))
        }
}
