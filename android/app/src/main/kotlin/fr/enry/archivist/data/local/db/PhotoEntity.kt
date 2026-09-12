package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

/** [TimelinePagingSource]'s own paging key — the anchor item's own `takenAt#photoId`
 * identity (same tiebreak as [PhotoDao.observeTimeline]) rather than a row offset, so a
 * generation restart re-anchors on the item itself and survives rows being inserted
 * anywhere else in the table mid-scroll. See that class's doc for why a row offset
 * doesn't. */
data class TimelineKey(val takenAt: String, val photoId: String)

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

    /** [TimelinePagingSource]'s three keyset queries — the local half of plan step
     * 2.11's `Pager`. Same ordering/tiebreak as [observeTimeline], unrelated to the
     * server's own opaque cursor string, which [TimelineCursorDao] tracks separately —
     * see that class's doc. `pageFromKey` is inclusive of the anchor itself (a refresh
     * re-centers on it, so it must reappear in the reloaded page); `pageAfter`/
     * `pageBefore` are exclusive, taking the last/first item of the adjacent page as
     * their own key so nothing repeats. `pageBefore`'s inner query walks *forward* in
     * time (`ASC`) to pick the nearest `limit` newer rows, then the outer query
     * re-sorts that small set back into the table's normal `DESC` order — an ordinary
     * `LIMIT` on the `DESC` query itself would instead keep the `limit` *oldest* of the
     * newer rows, which is `photoId`s closer to the anchor than the top of the range,
     * not the ones nearest it. */
    @Query("SELECT * FROM photos WHERE takenAt < :takenAt OR (takenAt = :takenAt AND photoId <= :photoId) ORDER BY takenAt DESC, photoId DESC LIMIT :limit")
    suspend fun pageFromKey(
        takenAt: String,
        photoId: String,
        limit: Int,
    ): List<PhotoEntity>

    @Query("SELECT * FROM photos ORDER BY takenAt DESC, photoId DESC LIMIT :limit")
    suspend fun pageFromStart(limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE takenAt < :afterTakenAt OR (takenAt = :afterTakenAt AND photoId < :afterPhotoId) ORDER BY takenAt DESC, photoId DESC LIMIT :limit")
    suspend fun pageAfter(
        afterTakenAt: String,
        afterPhotoId: String,
        limit: Int,
    ): List<PhotoEntity>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM photos
            WHERE takenAt > :beforeTakenAt OR (takenAt = :beforeTakenAt AND photoId > :beforePhotoId)
            ORDER BY takenAt ASC, photoId ASC
            LIMIT :limit
        ) ORDER BY takenAt DESC, photoId DESC
        """,
    )
    suspend fun pageBefore(
        beforeTakenAt: String,
        beforePhotoId: String,
        limit: Int,
    ): List<PhotoEntity>

    /** Newest-first, everything at or before [takenAt] — the candidates a jump to a
     * day chooses its landing from when the day is already cached. [takenAt] is a
     * fixed-width ISO bound, so the string comparison is chronological. */
    @Query("SELECT * FROM photos WHERE takenAt <= :takenAt ORDER BY takenAt DESC, photoId DESC LIMIT :limit")
    suspend fun pageAtOrBefore(
        takenAt: String,
        limit: Int,
    ): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String)

    @Query("DELETE FROM photos")
    suspend fun clear()

    /** [fr.enry.archivist.data.repo.TimelineRemoteMediator.initialize] uses this to tell
     * a genuine cold start (needs a network refresh) apart from a new `PagingSource`
     * generation caused by this table's own writes (doesn't) — see that function's doc. */
    @Query("SELECT NOT EXISTS(SELECT 1 FROM photos)")
    suspend fun isEmpty(): Boolean
}

/**
 * The calendar day this photo belongs to **in its own recorded offset**, not in the
 * viewer's timezone — the rule the timeline's date headers group by, and therefore the
 * only definition of "day" the user ever sees on this screen.
 *
 * Lives here rather than next to the grid because the fast-scroll jump has to agree with
 * it: picking a day off the rail selects a *calendar day*, and the photo it lands on is
 * chosen by this, so that the header above the landing shows the date the pill named.
 * Resolving the bound in the device's zone instead put the pill and the header one day
 * apart for any photo near a day boundary whose offset differs from the phone's — e.g. a
 * photo at 22:40:29Z with `tzOffsetMin = 120` is still 17 November on a UTC+1 phone but
 * is 18 November to itself, which is what the header shows.
 */
fun PhotoEntity.localDate(): LocalDate =
    Instant.parse(takenAt).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).toLocalDate()
