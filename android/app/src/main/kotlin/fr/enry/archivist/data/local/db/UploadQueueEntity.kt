package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Purely local — no server counterpart. Tracks one candidate file through
 * `android.md`'s "Upload pipeline": scan → extract metadata → thumbnail → upload.
 * [PENDING] is the state a row is queued in per that doc ("Row per file into a Room
 * `upload_queue` with state `PENDING`"); the rest mirror the pipeline's own stages so a
 * crash mid-import resumes at the right step rather than restarting it.
 */
enum class UploadState {
    PENDING,
    EXTRACTING,
    THUMBNAILING,
    UPLOADING,
    DONE,
    FAILED,
}

/**
 * Row per file to back up, durable across process death (a 500-photo import will
 * outlive the UI — android.md). [contentHash] is null until the scanner computes it,
 * and [photoId]/[renditionId] are null until `POST /uploads` assigns them — the client
 * proposes a path, the server decides identity and grouping (design.md, "Who
 * decides").
 */
@Entity(tableName = "upload_queue", indices = [Index("contentHash", unique = true), Index("state")])
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localUri: String,
    val displayName: String,
    val folderUri: String,
    val contentHash: String?,
    val state: UploadState,
    val takenAt: String?,
    val tzOffsetMin: Int?,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val photoId: String?,
    val renditionId: String?,
    val attempts: Int,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Dao
interface UploadQueueDao {
    /** Returns the generated [UploadQueueEntity.id] — the caller needs it for a later
     * [update]. Plain `@Insert` rather than `@Upsert`/an `ON CONFLICT` clause: a queued
     * row's `id` is always 0 (autogenerate) at insert time, so there's nothing to
     * conflict on yet — see [update] for advancing an existing row instead. */
    @Insert
    suspend fun insert(entry: UploadQueueEntity): Long

    @Query(
        """
        UPDATE upload_queue SET
            contentHash = :contentHash,
            state = :state,
            takenAt = :takenAt,
            tzOffsetMin = :tzOffsetMin,
            mime = :mime,
            width = :width,
            height = :height,
            photoId = :photoId,
            renditionId = :renditionId,
            attempts = :attempts,
            lastError = :lastError,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: Long,
        contentHash: String?,
        state: UploadState,
        takenAt: String?,
        tzOffsetMin: Int?,
        mime: String?,
        width: Int?,
        height: Int?,
        photoId: String?,
        renditionId: String?,
        attempts: Int,
        lastError: String?,
        updatedAt: String,
    )

    suspend fun update(entry: UploadQueueEntity) =
        update(
            entry.id,
            entry.contentHash,
            entry.state,
            entry.takenAt,
            entry.tzOffsetMin,
            entry.mime,
            entry.width,
            entry.height,
            entry.photoId,
            entry.renditionId,
            entry.attempts,
            entry.lastError,
            entry.updatedAt,
        )

    @Query("SELECT * FROM upload_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE state != :state ORDER BY createdAt ASC")
    fun observePending(state: UploadState = UploadState.DONE): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): UploadQueueEntity?

    @Query("DELETE FROM upload_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_queue WHERE state = :state")
    suspend fun deleteByState(state: UploadState)

    @Query("DELETE FROM upload_queue")
    suspend fun clear()
}
