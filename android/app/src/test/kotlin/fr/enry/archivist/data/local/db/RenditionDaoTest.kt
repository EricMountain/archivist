package fr.enry.archivist.data.local.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RenditionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var photoDao: PhotoDao
    private lateinit var dao: RenditionDao

    private fun photo(photoId: String) =
        PhotoEntity(
            photoId = photoId,
            takenAt = "2026-07-13T16:48:20.000Z",
            tzOffsetMin = 540,
            mime = "image/jpeg",
            width = 8192,
            height = 5464,
            status = AssetStatus.READY,
            thumbs = emptyMap(),
            encDek = "b64dek",
            encKeyId = "mk-3",
        )

    private fun rendition(
        renditionId: String,
        photoId: String,
        role: RenditionRole = RenditionRole.DISPLAY,
        contentHash: String = "hmac-sha256:$renditionId",
    ) = RenditionEntity(
        renditionId = renditionId,
        photoId = photoId,
        role = role,
        path = "2026/07-japan/IMG_8123.JPG",
        ext = "jpg",
        mime = "image/jpeg",
        contentHash = contentHash,
        bytes = 8388624,
        plainBytes = 8388608,
    )

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        photoDao = db.photoDao()
        dao = db.renditionDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a photo can have two renditions, RAW and JPEG`() =
        runTest {
            photoDao.upsertAll(listOf(photo("p1")))
            dao.upsertAll(
                listOf(
                    rendition("r-raw", "p1", role = RenditionRole.RAW),
                    rendition("r-jpg", "p1", role = RenditionRole.DISPLAY),
                ),
            )

            val renditions = dao.observeByPhotoId("p1").first()

            assertEquals(setOf(RenditionRole.RAW, RenditionRole.DISPLAY), renditions.map { it.role }.toSet())
        }

    @Test
    fun `existsByContentHash finds an already-backed-up file so a rescan skips it`() =
        runTest {
            photoDao.upsertAll(listOf(photo("p1")))
            dao.upsertAll(listOf(rendition("r1", "p1", contentHash = "hmac-sha256:abc123")))

            assertTrue(dao.existsByContentHash("hmac-sha256:abc123"))
            assertFalse(dao.existsByContentHash("hmac-sha256:never-uploaded"))
        }

    @Test
    fun `deleting the parent photo cascades to its renditions`() =
        runTest {
            photoDao.upsertAll(listOf(photo("p1")))
            dao.upsertAll(listOf(rendition("r1", "p1")))

            photoDao.deleteByPhotoId("p1")

            assertEquals(emptyList<RenditionEntity>(), dao.observeByPhotoId("p1").first())
        }
}
