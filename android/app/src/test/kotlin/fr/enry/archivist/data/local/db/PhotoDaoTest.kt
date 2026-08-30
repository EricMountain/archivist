package fr.enry.archivist.data.local.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PhotoDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoDao

    private fun photo(
        photoId: String,
        takenAt: String,
    ) = PhotoEntity(
        photoId = photoId,
        takenAt = takenAt,
        tzOffsetMin = 540,
        mime = "image/heic",
        width = 4032,
        height = 3024,
        status = AssetStatus.READY,
        thumbs =
            mapOf(
                256 to ThumbEntry(bucket = "pa-derived", key = "th/o/p/256", iv = "iv256", bytes = 14336),
                1024 to ThumbEntry(bucket = "pa-derived", key = "th/o/p/1024", iv = "iv1024", bytes = 152576),
            ),
        encDek = "b64dek",
        encKeyId = "mk-3",
    )

    @BeforeEach
    fun setUp() {
        db = buildTestDatabase()
        dao = db.photoDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upserted photo round-trips including nested thumbs map`() =
        runTest {
            val entry = photo("p1", "2026-07-14T09:22:05.000Z")
            dao.upsertAll(listOf(entry))

            val stored = dao.getByPhotoId("p1")

            assertEquals(entry, stored)
        }

    @Test
    fun `upsert of an existing photoId replaces it rather than duplicating`() =
        runTest {
            dao.upsertAll(listOf(photo("p1", "2026-07-14T09:22:05.000Z")))
            dao.upsertAll(listOf(photo("p1", "2026-07-14T09:22:05.000Z").copy(status = AssetStatus.FAILED)))

            val timeline = dao.observeTimeline().first()

            assertEquals(1, timeline.size)
            assertEquals(AssetStatus.FAILED, timeline.single().status)
        }

    @Test
    fun `timeline orders newest takenAt first`() =
        runTest {
            dao.upsertAll(
                listOf(
                    photo("older", "2026-06-01T00:00:00.000Z"),
                    photo("newer", "2026-07-01T00:00:00.000Z"),
                ),
            )

            val timeline = dao.observeTimeline().first()

            assertEquals(listOf("newer", "older"), timeline.map { it.photoId })
        }

    @Test
    fun `same-instant burst frames tiebreak on photoId descending`() =
        runTest {
            // Mirrors A6/A7 in sample-data.md: identical takenAt, ordering must still
            // be total so cursor pagination can't skip or repeat a row.
            dao.upsertAll(
                listOf(
                    photo("01K5A2QKB8YM5RVT7NQXHD2WFG", "2026-07-15T11:03:12.000Z"),
                    photo("01K5A2QMD3ZQ8WKN6BVYTX4HRP", "2026-07-15T11:03:12.000Z"),
                ),
            )

            val timeline = dao.observeTimeline().first()

            assertEquals(
                listOf("01K5A2QMD3ZQ8WKN6BVYTX4HRP", "01K5A2QKB8YM5RVT7NQXHD2WFG"),
                timeline.map { it.photoId },
            )
        }

    @Test
    fun `deleting by photoId removes it and nothing else`() =
        runTest {
            dao.upsertAll(listOf(photo("keep", "2026-07-01T00:00:00.000Z"), photo("gone", "2026-07-02T00:00:00.000Z")))

            dao.deleteByPhotoId("gone")

            assertNull(dao.getByPhotoId("gone"))
            assertEquals("keep", dao.getByPhotoId("keep")?.photoId)
        }

    @Test
    fun `clear empties the table`() =
        runTest {
            dao.upsertAll(listOf(photo("p1", "2026-07-01T00:00:00.000Z")))

            dao.clear()

            assertEquals(emptyList<PhotoEntity>(), dao.observeTimeline().first())
        }
}
