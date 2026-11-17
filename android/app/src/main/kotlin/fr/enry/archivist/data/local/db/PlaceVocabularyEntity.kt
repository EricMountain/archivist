package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The cached answer to `GET /places` — the owner's place vocabulary (design.md "Place
 * vocabulary (query 16)"), plus the `ETag` it arrived with. Geolocation plan step 8's
 * search matches against it on the device.
 *
 * Cached the same way as [HistogramEntity], for the same reasons: one row, read whole,
 * small (hundreds to a few thousand places), changing only when a located photo is
 * added, trashed, restored or re-geocoded. [etag] goes back as `If-None-Match`, so a
 * revalidation is usually a bodiless 304; and a cached row lets search work offline.
 *
 * [body] is the response's JSON as received (`PlacesResponse`), decoded by
 * [fr.enry.archivist.data.repo.PlaceRepository]: nothing queries inside it, so a column
 * per field would buy nothing.
 */
@Entity(tableName = "place_vocabulary")
data class PlaceVocabularyEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val body: String,
    /** The server's `ETag` (`"pl-<version>"`), replayed as `If-None-Match`. */
    val etag: String?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

@Dao
interface PlaceVocabularyDao {
    @Query("SELECT * FROM place_vocabulary WHERE id = :id")
    fun observe(id: Int = PlaceVocabularyEntity.SINGLETON_ID): Flow<PlaceVocabularyEntity?>

    @Query("SELECT * FROM place_vocabulary WHERE id = :id")
    suspend fun get(id: Int = PlaceVocabularyEntity.SINGLETON_ID): PlaceVocabularyEntity?

    /** `ON CONFLICT DO UPDATE`, not `@Upsert` — see [HistogramDao.setOne]. */
    @Query(
        """
        INSERT INTO place_vocabulary (id, body, etag) VALUES (0, :body, :etag)
        ON CONFLICT(id) DO UPDATE SET body = excluded.body, etag = excluded.etag
        """,
    )
    suspend fun set(
        body: String,
        etag: String?,
    )

    @Query("DELETE FROM place_vocabulary")
    suspend fun clear()

    /** Forgets the cached `ETag` but keeps the body, so the next sync is an unconditional
     * fetch while offline search still has something to match against. Called after this
     * device changed a photo's place ("Update location"). */
    @Query("UPDATE place_vocabulary SET etag = NULL")
    suspend fun dropEtag()
}
