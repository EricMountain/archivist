package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
 * decides"). [plainBytes]/[fileMtimeEpochSec] come from `MediaStore` at scan time (free,
 * already in hand — see [fr.enry.archivist.sync.Scanner]); [takenAt]/[tzOffsetMin]/
 * [mime]/[width]/[height]/[takenAtSrc]/[tzSrc] are filled in later, by plan step 2.10's
 * upload worker, once it's actually read the file's EXIF.
 */
@Entity(
    tableName = "upload_queue",
    indices = [Index("contentHash", unique = true), Index("localUri", unique = true), Index("state")],
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localUri: String,
    val displayName: String,
    val folderUri: String,
    val contentHash: String?,
    val state: UploadState,
    val plainBytes: Long?,
    val fileMtimeEpochSec: Long?,
    val takenAt: String?,
    val tzOffsetMin: Int?,
    /** Wire value of [fr.enry.archivist.domain.TakenAtSrc] — plain `String`, not the
     * enum itself, matching how every other wire-shaped column here (`mime`, `state`
     * aside) is stored; Room's `Converters` are for types that don't already have an
     * obvious wire string. */
    val takenAtSrc: String?,
    val tzSrc: String?,
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

    /** Same as [insert], except a `contentHash` collision (two different local files
     * with identical bytes — a real scenario, e.g. an auto-backup app copying camera
     * photos into a second folder) is silently ignored rather than thrown: the content
     * is already queued or handled under its other URI, so there's nothing more to do
     * for this one. Plan step 2.7's scanner uses this, not [insert] — and must check
     * the returned id: Room/SQLite report `-1` when `INSERT OR IGNORE` drops the row,
     * the only way to tell "ignored" apart from "inserted" since both otherwise look
     * identical from the caller's side. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNewContent(entry: UploadQueueEntity): Long

    @Query(
        """
        UPDATE upload_queue SET
            contentHash = :contentHash,
            state = :state,
            plainBytes = :plainBytes,
            fileMtimeEpochSec = :fileMtimeEpochSec,
            takenAt = :takenAt,
            tzOffsetMin = :tzOffsetMin,
            takenAtSrc = :takenAtSrc,
            tzSrc = :tzSrc,
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
        plainBytes: Long?,
        fileMtimeEpochSec: Long?,
        takenAt: String?,
        tzOffsetMin: Int?,
        takenAtSrc: String?,
        tzSrc: String?,
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
            entry.plainBytes,
            entry.fileMtimeEpochSec,
            entry.takenAt,
            entry.tzOffsetMin,
            entry.takenAtSrc,
            entry.tzSrc,
            entry.mime,
            entry.width,
            entry.height,
            entry.photoId,
            entry.renditionId,
            entry.attempts,
            entry.lastError,
            entry.updatedAt,
        )

    @Query("SELECT * FROM upload_queue WHERE id = :id")
    suspend fun getById(id: Long): UploadQueueEntity?

    /** Every row [fr.enry.archivist.sync.UploadWorker] still has work to do on —
     * everything except [UploadState.DONE] (finished) and [UploadState.FAILED]
     * (permanently gave up; a retriable failure is WorkManager's own `Result.retry()`,
     * which keeps a row's state at whatever stage it failed in, not [UploadState.FAILED]
     * — see [fr.enry.archivist.data.repo.UploadRepository]). Used both right after a
     * scan and at app startup, to re-enqueue anything a process death might have dropped
     * before WorkManager itself could persist it. */
    @Query(
        "SELECT id FROM upload_queue WHERE state IN ('PENDING', 'EXTRACTING', 'THUMBNAILING', 'UPLOADING')",
    )
    suspend fun getActiveIds(): List<Long>

    @Query("SELECT * FROM upload_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE state != :state ORDER BY createdAt ASC")
    fun observePending(state: UploadState = UploadState.DONE): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): UploadQueueEntity?

    /** Plan step 2.7's scanner checks this before re-hashing a file it's already seen
     * — computing [UploadQueueEntity.contentHash] means reading the whole file once,
     * and a row existing here (whatever its state) means that's already been done for
     * this exact local file. */
    @Query("SELECT * FROM upload_queue WHERE localUri = :localUri LIMIT 1")
    suspend fun getByLocalUri(localUri: String): UploadQueueEntity?

    /** Plan step 2.13: every local file this device has uploaded as part of one
     * server-side asset — normally one row (a single JPEG), but a grouped multi-
     * rendition asset (`IMG_1.CR3` + `IMG_1.JPG`) has one row per rendition, each
     * scanned and uploaded as its own local file. [fr.enry.archivist.data.repo.DeleteRepository]
     * uses this both to find what to tombstone (by [UploadQueueEntity.contentHash]) and,
     * for "remove from both", what to delete via `MediaStore` (by
     * [UploadQueueEntity.localUri]) — this table, not the still-unpopulated `renditions`
     * table (see 2.6/2.10's STATUS.md notes), is the only place a photoId maps back to
     * a local file on this device. */
    @Query("SELECT * FROM upload_queue WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: String): List<UploadQueueEntity>

    @Query("DELETE FROM upload_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_queue WHERE state = :state")
    suspend fun deleteByState(state: UploadState)

    @Query("DELETE FROM upload_queue")
    suspend fun clear()
}
