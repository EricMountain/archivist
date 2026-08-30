package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One entry of `#META`'s `thumbs` map — see "Attributes" in `design.md`. `bytes` is
 * ciphertext length, not plaintext. */
@Serializable
data class ThumbEntry(
    val bucket: String,
    val key: String,
    val iv: String,
    val bytes: Long,
)

/** Mirrors `AssetStatus` in `src/core/items.ts` — deletion is a separate axis (trashing
 * rewrites `timelinePk`, see design.md), not a value here. */
enum class AssetStatus {
    PROCESSING,
    READY,
    FAILED,
}

/**
 * The timeline cache — mirrors `timeline_gsi`'s own projection (`design.md`'s
 * `Projection: INCLUDE [thumbs, encDek, encKeyId, width, height, mime, tzOffsetMin,
 * status]`) plus [photoId] and [takenAt], both recovered server-side from the GSI key
 * (`pk` / `timelineSk`) rather than projected as their own attributes — see `dto.ts`.
 * `path`/`stem` are deliberately absent, matching the server projection: the grid
 * doesn't need them.
 *
 * Room is the source of truth the UI reads (`android.md`, "Architecture") — this table
 * is filled by a `RemoteMediator` against `GET /photos` (plan step 2.11), never written
 * to speculatively by the upload pipeline; a locally-queued file only appears here once
 * the server has actually assigned it a `photoId`.
 */
@Entity(tableName = "photos", indices = [Index("takenAt")])
data class PhotoEntity(
    @PrimaryKey val photoId: String,
    /** ISO-8601 UTC instant, denormalized from `timelineSk`'s `<takenAt>#<photoId>` —
     * Room needs it as its own sortable column since it can't split a compound key. */
    val takenAt: String,
    val tzOffsetMin: Int,
    val mime: String,
    val width: Int,
    val height: Int,
    val status: AssetStatus,
    val thumbs: Map<Int, ThumbEntry>,
    val encDek: String,
    val encKeyId: String,
)

@Dao
interface PhotoDao {
    /** Not `@Upsert`: Room's generated upsert (insert, catch the constraint violation,
     * fall back to update) depends on parsing the underlying exception's message to
     * recognise a uniqueness conflict — and `androidx.sqlite:sqlite-bundled-jvm`
     * (the driver the DAO tests run on, see `TestDatabase.kt`) throws that exception
     * with a null message, so the fallback never fires and a re-upsert of an existing
     * `photoId` crashes instead of updating. A single `ON CONFLICT DO UPDATE` statement
     * sidesteps the whole exception-parsing path — SQLite resolves the conflict itself. */
    @Query(
        """
        INSERT INTO photos (photoId, takenAt, tzOffsetMin, mime, width, height, status, thumbs, encDek, encKeyId)
        VALUES (:photoId, :takenAt, :tzOffsetMin, :mime, :width, :height, :status, :thumbs, :encDek, :encKeyId)
        ON CONFLICT(photoId) DO UPDATE SET
            takenAt = excluded.takenAt,
            tzOffsetMin = excluded.tzOffsetMin,
            mime = excluded.mime,
            width = excluded.width,
            height = excluded.height,
            status = excluded.status,
            thumbs = excluded.thumbs,
            encDek = excluded.encDek,
            encKeyId = excluded.encKeyId
        """,
    )
    suspend fun upsertOne(
        photoId: String,
        takenAt: String,
        tzOffsetMin: Int,
        mime: String,
        width: Int,
        height: Int,
        status: AssetStatus,
        thumbs: Map<Int, ThumbEntry>,
        encDek: String,
        encKeyId: String,
    )

    @Transaction
    suspend fun upsertAll(photos: List<PhotoEntity>) {
        for (photo in photos) {
            upsertOne(
                photo.photoId,
                photo.takenAt,
                photo.tzOffsetMin,
                photo.mime,
                photo.width,
                photo.height,
                photo.status,
                photo.thumbs,
                photo.encDek,
                photo.encKeyId,
            )
        }
    }

    /** `photoId` breaks ties the same way `timelineSk`'s `#<photoId>` suffix does
     * server-side (see design.md, A6/A7 in sample-data.md), so a page boundary never
     * splits a same-instant pair differently than the server would. */
    @Query("SELECT * FROM photos ORDER BY takenAt DESC, photoId DESC")
    fun observeTimeline(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String)

    @Query("DELETE FROM photos")
    suspend fun clear()
}
