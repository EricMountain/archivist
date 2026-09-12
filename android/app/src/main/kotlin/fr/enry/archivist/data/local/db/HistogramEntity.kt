package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The cached answer to `GET /photos/histogram` — how many live photos fall on each
 * local day (design.md pattern 15), plus the `ETag` it arrived with.
 *
 * One row, always. The histogram describes the whole library rather than any window of
 * it, and it is small enough (a few tens of kilobytes for a decade) that splitting it
 * into a row per day would buy nothing and cost a join on every scrollbar frame.
 *
 * Cached rather than re-fetched because the scrollbar needs it *immediately* on a long
 * press, and because it changes only when a photo is added or trashed. [etag] is what
 * makes revalidating nearly free: it goes back as `If-None-Match`, and the usual answer
 * is a bodiless 304 that costs the server one item read.
 */
@Entity(tableName = "timeline_histogram")
data class HistogramEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** `yyyy-mm-dd` to count, serialised by [Converters]. Days with no photos absent. */
    val days: Map<String, Int>,
    val total: Int,
    /** The server's `ETag`, replayed as `If-None-Match`. */
    val etag: String?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

@Dao
interface HistogramDao {
    @Query("SELECT * FROM timeline_histogram WHERE id = :id")
    fun observe(id: Int = HistogramEntity.SINGLETON_ID): Flow<HistogramEntity?>

    @Query("SELECT * FROM timeline_histogram WHERE id = :id")
    suspend fun get(id: Int = HistogramEntity.SINGLETON_ID): HistogramEntity?

    /** Not `@Upsert` — it is unreliable on the JVM test driver; see `android/AGENTS.md`
     * and the note on `PhotoDao.upsertOne`. One `ON CONFLICT DO UPDATE` statement lets
     * SQLite resolve the conflict itself. */
    @Query(
        """
        INSERT INTO timeline_histogram (id, days, total, etag) VALUES (0, :days, :total, :etag)
        ON CONFLICT(id) DO UPDATE SET days = excluded.days, total = excluded.total, etag = excluded.etag
        """,
    )
    suspend fun setOne(
        days: Map<String, Int>,
        total: Int,
        etag: String?,
    )

    suspend fun set(entity: HistogramEntity) = setOne(entity.days, entity.total, entity.etag)

    @Query("DELETE FROM timeline_histogram")
    suspend fun clear()
}
