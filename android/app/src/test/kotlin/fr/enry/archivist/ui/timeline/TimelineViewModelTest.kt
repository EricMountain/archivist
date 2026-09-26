package fr.enry.archivist.ui.timeline

import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.localDate
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun photo(
    id: String,
    takenAt: String,
    tzOffsetMin: Int = 0,
) = PhotoEntity(
    photoId = id,
    takenAt = takenAt,
    tzOffsetMin = tzOffsetMin,
    mime = "image/jpeg",
    width = 10,
    height = 10,
    status = AssetStatus.READY,
    thumbs = emptyMap(),
    encDek = "dek",
    encKeyId = "mk-1",
)

class TimelineViewModelTest {
    @Test
    fun `localDate uses the offset, not UTC`() {
        // 2024-01-01T23:30:00Z at UTC+2 is 2024-01-02T01:30 local.
        assertEquals(LocalDate.of(2024, 1, 2), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 120).localDate())
        assertEquals(LocalDate.of(2024, 1, 1), photo("p", "2024-01-01T23:30:00.000Z", tzOffsetMin = 0).localDate())
    }
}
