package fr.enry.archivist.data.local.db

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DeviceDao

    private fun device(
        deviceKey: String = "canon|eos r5|001",
        label: String = deviceKey,
        tzOffsetMin: Int? = null,
        photoCount: Int = 1,
    ) = DeviceEntity(deviceKey, label, tzOffsetMin, photoCount, firstSeenAt = "2026-08-30T10:00:00.000Z")

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        dao = db.deviceDao()
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `upsert inserts a new device`() =
        runTest {
            dao.upsert(device())
            assertEquals("canon|eos r5|001", dao.getByDeviceKey("canon|eos r5|001")?.deviceKey)
        }

    @Test
    fun `upsert of an existing key updates rather than duplicating`() =
        runTest {
            dao.upsert(device(tzOffsetMin = null, photoCount = 1))
            dao.upsert(device(label = "Dad's R5", tzOffsetMin = 540, photoCount = 2))

            val all = dao.getAll()
            assertEquals(1, all.size)
            assertEquals("Dad's R5", all[0].label)
            assertEquals(540, all[0].tzOffsetMin)
            assertEquals(2, all[0].photoCount)
        }

    @Test
    fun `clear removes every row -- a full refresh replaces the cache wholesale`() =
        runTest {
            dao.upsert(device("a"))
            dao.upsert(device("b"))
            dao.clear()
            assertTrue(dao.getAll().isEmpty())
        }

    @Test
    fun `deleteByDeviceKey removes only that row`() =
        runTest {
            dao.upsert(device("a"))
            dao.upsert(device("b"))
            dao.deleteByDeviceKey("a")
            assertNull(dao.getByDeviceKey("a"))
            assertEquals("b", dao.getByDeviceKey("b")?.deviceKey)
        }

    @Test
    fun `getByDeviceKey returns null for a device never seen`() =
        runTest {
            assertNull(dao.getByDeviceKey("never|seen|1"))
        }
}
