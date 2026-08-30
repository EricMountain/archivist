package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Mirrors an `R#` item's `role` — see the `F#REND#` facet vocabulary in
 * `design.md`. */
enum class RenditionRole {
    DISPLAY,
    RAW,
    MOTION,
    SIDECAR,
}

/**
 * One physical file within a photo's partition — a JPEG, a RAW sibling, a Live Photo's
 * motion clip. [contentHash] is what plan step 2.7's scanner checks a candidate file
 * against before queuing it (the plan's own shorthand is "hash in `photos`", but the
 * hash lives on the rendition, not `#META` — see `sample-data.md`'s `R#` items).
 */
@Entity(
    tableName = "renditions",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["photoId"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("photoId"), Index("contentHash", unique = true)],
)
data class RenditionEntity(
    @PrimaryKey val renditionId: String,
    val photoId: String,
    val role: RenditionRole,
    val path: String,
    val ext: String,
    val mime: String,
    val contentHash: String,
    val bytes: Long,
    val plainBytes: Long,
)

@Dao
interface RenditionDao {
    /** Not `@Upsert` — see the note on `PhotoDao.upsertOne`. */
    @Query(
        """
        INSERT INTO renditions (renditionId, photoId, role, path, ext, mime, contentHash, bytes, plainBytes)
        VALUES (:renditionId, :photoId, :role, :path, :ext, :mime, :contentHash, :bytes, :plainBytes)
        ON CONFLICT(renditionId) DO UPDATE SET
            photoId = excluded.photoId,
            role = excluded.role,
            path = excluded.path,
            ext = excluded.ext,
            mime = excluded.mime,
            contentHash = excluded.contentHash,
            bytes = excluded.bytes,
            plainBytes = excluded.plainBytes
        """,
    )
    suspend fun upsertOne(
        renditionId: String,
        photoId: String,
        role: RenditionRole,
        path: String,
        ext: String,
        mime: String,
        contentHash: String,
        bytes: Long,
        plainBytes: Long,
    )

    @Transaction
    suspend fun upsertAll(renditions: List<RenditionEntity>) {
        for (rendition in renditions) {
            upsertOne(
                rendition.renditionId,
                rendition.photoId,
                rendition.role,
                rendition.path,
                rendition.ext,
                rendition.mime,
                rendition.contentHash,
                rendition.bytes,
                rendition.plainBytes,
            )
        }
    }

    @Query("SELECT * FROM renditions WHERE photoId = :photoId")
    fun observeByPhotoId(photoId: String): Flow<List<RenditionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM renditions WHERE contentHash = :contentHash)")
    suspend fun existsByContentHash(contentHash: String): Boolean

    @Query("DELETE FROM renditions WHERE renditionId = :renditionId")
    suspend fun deleteByRenditionId(renditionId: String)

    @Query("DELETE FROM renditions")
    suspend fun clear()
}
