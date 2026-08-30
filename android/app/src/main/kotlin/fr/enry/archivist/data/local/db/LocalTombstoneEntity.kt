package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * "content hash → deleted, so a scan skips it" (plan step 2.6's own wording). Keyed by
 * content hash rather than local URI, since the same file re-appears at a new URI after
 * a re-scan (moved, renamed, restored from a backup) and the point is to recognise the
 * *content* was already deliberately removed from the local backup set, not the path.
 */
@Entity(tableName = "local_tombstones")
data class LocalTombstoneEntity(
    @PrimaryKey val contentHash: String,
    val deletedAt: String,
)

@Dao
interface LocalTombstoneDao {
    /** Not `@Upsert` — see the note on `PhotoDao.upsertOne`. */
    @Query(
        """
        INSERT INTO local_tombstones (contentHash, deletedAt)
        VALUES (:contentHash, :deletedAt)
        ON CONFLICT(contentHash) DO UPDATE SET deletedAt = excluded.deletedAt
        """,
    )
    suspend fun upsertOne(
        contentHash: String,
        deletedAt: String,
    )

    suspend fun upsert(tombstone: LocalTombstoneEntity) = upsertOne(tombstone.contentHash, tombstone.deletedAt)

    @Query("SELECT EXISTS(SELECT 1 FROM local_tombstones WHERE contentHash = :contentHash)")
    suspend fun exists(contentHash: String): Boolean

    @Query("DELETE FROM local_tombstones WHERE contentHash = :contentHash")
    suspend fun delete(contentHash: String)

    @Query("DELETE FROM local_tombstones")
    suspend fun clear()
}
