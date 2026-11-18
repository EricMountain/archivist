package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Timeline place filter (plan `timeline-place-filter.md`, F1, "Data path"): a
 * **client-only session cache** of per-place facet streams, `GET /facets/{type}/{id}`
 * read newest first, one page at a time, only as the filtered grid scrolls. It mirrors no
 * server item: rows are what this session has fetched, and the cache is emptied when the
 * filter is cleared and on a cold start (F13).
 *
 * One row per stream that has had at least one page. **No row means no page yet**, which
 * blocks the merged view (see [FacetStreamDao]) until that stream's first page is in.
 *
 * [cursor] is the server's opaque cursor for the next page; [oldestTakenAt]/[oldestPhotoId]
 * the `takenAt#photoId` of the oldest row fetched so far (null only if every page so far
 * came back empty with a cursor, which also blocks); [done] that the server said there
 * are no more pages.
 */
@Entity(tableName = "facet_streams")
data class FacetStreamEntity(
    /** `<facetType>#<value>`, e.g. `REGION#JP.22`, `PLACE#1857910`. */
    @PrimaryKey val streamKey: String,
    val cursor: String?,
    val oldestTakenAt: String?,
    val oldestPhotoId: String?,
    val done: Boolean,
    /** ISO-8601 UTC, when the last page landed. Diagnostic only. */
    val fetchedAt: String,
)

/**
 * One fetched facet entry, as the grid cell needs it ([PhotoEntity]'s display columns,
 * without `preview`: facet items carry none, so there are no grid video previews in
 * filter mode), keyed by `(streamKey, photoId)`. A photo can sit in two selected streams
 * (a town and a region F5 failed to link); the merged queries collapse it, keeping the
 * row with the highest [specificity].
 *
 * The place columns come from the stream (F12): a town stream gives the town's name and
 * id, a region stream the region's, a country stream none (the bubble then shows the date
 * only). [localDay] is the photo's day in its own offset (`PhotoEntity.localDate`), for
 * the loaded histogram and local jumps.
 */
@Entity(
    tableName = "facet_stream_rows",
    primaryKeys = ["streamKey", "photoId"],
    indices = [Index("photoId"), Index(value = ["streamKey", "takenAt"])],
)
data class FacetStreamRowEntity(
    val streamKey: String,
    val photoId: String,
    val takenAt: String,
    val tzOffsetMin: Int,
    val mime: String,
    val width: Int,
    val height: Int,
    val status: AssetStatus,
    val thumbs: Map<Int, ThumbEntry>,
    val encDek: String,
    val encKeyId: String,
    val placeCc: String?,
    val placeName: String?,
    val placeKey: String?,
    /** 2 for a town stream, 1 for a region, 0 for a country: which row wins the dedup. */
    val specificity: Int,
    /** `yyyy-MM-dd`. */
    val localDay: String,
)

/** One day of the loaded rows' histogram. */
data class FacetDayCount(val day: String, val n: Int)

// The merged view, as SQL fragments shared by every query below. Parameters:
// `:streams` the selected stream keys; `:wTakenAt`/`:wPhotoId` the watermark (both null:
// no limit, every selected stream is done). Rows at or above the watermark are complete
// across every selected stream, so the grid never shows a gap that fills in later.

/** The [PhotoEntity] columns, from alias `r`. */
private const val COLUMNS =
    "r.photoId AS photoId, r.takenAt AS takenAt, r.tzOffsetMin AS tzOffsetMin, r.mime AS mime, " +
        "r.width AS width, r.height AS height, r.status AS status, r.thumbs AS thumbs, r.encDek AS encDek, " +
        "r.encKeyId AS encKeyId, NULL AS preview, r.placeCc AS placeCc, r.placeName AS placeName, r.placeKey AS placeKey"

/** Selected, at or above the watermark, and the one row per `photoId` that wins: highest
 * specificity, then lowest stream key, so the choice is total. */
private const val MERGED =
    "r.streamKey IN (:streams) " +
        "AND (:wTakenAt IS NULL OR r.takenAt > :wTakenAt OR (r.takenAt = :wTakenAt AND r.photoId >= :wPhotoId)) " +
        "AND NOT EXISTS (SELECT 1 FROM facet_stream_rows o WHERE o.photoId = r.photoId AND o.streamKey IN (:streams) " +
        "AND (o.specificity > r.specificity OR (o.specificity = r.specificity AND o.streamKey < r.streamKey)))"

private const val NEWEST_FIRST = " ORDER BY r.takenAt DESC, r.photoId DESC"

@Dao
interface FacetStreamDao {
    @Query("SELECT * FROM facet_streams WHERE streamKey IN (:keys)")
    suspend fun streams(keys: Collection<String>): List<FacetStreamEntity>

    @Query("SELECT * FROM facet_streams WHERE streamKey IN (:keys)")
    fun observeStreams(keys: Collection<String>): Flow<List<FacetStreamEntity>>

    @Query("SELECT * FROM facet_streams WHERE streamKey = :key")
    suspend fun stream(key: String): FacetStreamEntity?

    /** `ON CONFLICT DO UPDATE`, not `@Upsert`: see `PhotoDao.upsertOne`. */
    @Query(
        """
        INSERT INTO facet_streams (streamKey, cursor, oldestTakenAt, oldestPhotoId, done, fetchedAt)
        VALUES (:streamKey, :cursor, :oldestTakenAt, :oldestPhotoId, :done, :fetchedAt)
        ON CONFLICT(streamKey) DO UPDATE SET
            cursor = excluded.cursor,
            oldestTakenAt = excluded.oldestTakenAt,
            oldestPhotoId = excluded.oldestPhotoId,
            done = excluded.done,
            fetchedAt = excluded.fetchedAt
        """,
    )
    suspend fun upsertStreamColumns(
        streamKey: String,
        cursor: String?,
        oldestTakenAt: String?,
        oldestPhotoId: String?,
        done: Boolean,
        fetchedAt: String,
    )

    suspend fun upsertStream(s: FacetStreamEntity) =
        upsertStreamColumns(s.streamKey, s.cursor, s.oldestTakenAt, s.oldestPhotoId, s.done, s.fetchedAt)

    @Query(
        """
        INSERT INTO facet_stream_rows (streamKey, photoId, takenAt, tzOffsetMin, mime, width, height, status, thumbs,
            encDek, encKeyId, placeCc, placeName, placeKey, specificity, localDay)
        VALUES (:streamKey, :photoId, :takenAt, :tzOffsetMin, :mime, :width, :height, :status, :thumbs,
            :encDek, :encKeyId, :placeCc, :placeName, :placeKey, :specificity, :localDay)
        ON CONFLICT(streamKey, photoId) DO UPDATE SET
            takenAt = excluded.takenAt,
            tzOffsetMin = excluded.tzOffsetMin,
            mime = excluded.mime,
            width = excluded.width,
            height = excluded.height,
            status = excluded.status,
            thumbs = excluded.thumbs,
            encDek = excluded.encDek,
            encKeyId = excluded.encKeyId,
            placeCc = excluded.placeCc,
            placeName = excluded.placeName,
            placeKey = excluded.placeKey,
            specificity = excluded.specificity,
            localDay = excluded.localDay
        """,
    )
    suspend fun upsertRowColumns(
        streamKey: String,
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
        placeCc: String?,
        placeName: String?,
        placeKey: String?,
        specificity: Int,
        localDay: String,
    )

    @Transaction
    suspend fun upsertRows(rows: List<FacetStreamRowEntity>) {
        for (r in rows) {
            upsertRowColumns(
                r.streamKey, r.photoId, r.takenAt, r.tzOffsetMin, r.mime, r.width, r.height, r.status, r.thumbs,
                r.encDek, r.encKeyId, r.placeCc, r.placeName, r.placeKey, r.specificity, r.localDay,
            )
        }
    }

    /** Every cached row of one stream, newest first. Tests and diagnostics. */
    @Query("SELECT * FROM facet_stream_rows WHERE streamKey = :key ORDER BY takenAt DESC, photoId DESC")
    suspend fun rowsOf(key: String): List<FacetStreamRowEntity>

    /** Every cached row of one photo, whichever selected streams hold it (row patching,
     * F13). */
    @Query("SELECT * FROM facet_stream_rows WHERE photoId = :photoId")
    suspend fun rowsOfPhoto(photoId: String): List<FacetStreamRowEntity>

    /** An edit made on this device (date, rotate, repair; F13): the photo's display columns
     * in every stream that holds it. The place columns stay the stream's own (F12). */
    @Query(
        """
        UPDATE facet_stream_rows SET takenAt = :takenAt, tzOffsetMin = :tzOffsetMin, mime = :mime, width = :width,
            height = :height, status = :status, thumbs = :thumbs, encDek = :encDek, encKeyId = :encKeyId,
            localDay = :localDay
        WHERE photoId = :photoId
        """,
    )
    suspend fun patchDisplay(
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
        localDay: String,
    ): Int

    @Query(
        "UPDATE facet_stream_rows SET placeCc = :placeCc, placeName = :placeName, placeKey = :placeKey " +
            "WHERE streamKey = :streamKey AND photoId = :photoId",
    )
    suspend fun setRowPlace(
        streamKey: String,
        photoId: String,
        placeCc: String?,
        placeName: String?,
        placeKey: String?,
    )

    /** A photo deleted on this device leaves every stream (F13). */
    @Query("DELETE FROM facet_stream_rows WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String): Int

    @Query("DELETE FROM facet_stream_rows")
    suspend fun clearRows()

    @Query("DELETE FROM facet_streams")
    suspend fun clearStreams()

    // The merged view's keyset queries: the same shapes, ordering and tiebreak as
    // PhotoDao's, which TimelinePagingSource pages over.

    @Query("SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED$NEWEST_FIRST LIMIT :limit")
    suspend fun mergedFromStart(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
        limit: Int,
    ): List<PhotoEntity>

    @Query(
        "SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED " +
            "AND (r.takenAt < :takenAt OR (r.takenAt = :takenAt AND r.photoId <= :photoId))$NEWEST_FIRST LIMIT :limit",
    )
    suspend fun mergedFromKey(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
        takenAt: String,
        photoId: String,
        limit: Int,
    ): List<PhotoEntity>

    @Query(
        "SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED " +
            "AND (r.takenAt < :takenAt OR (r.takenAt = :takenAt AND r.photoId < :photoId))$NEWEST_FIRST LIMIT :limit",
    )
    suspend fun mergedAfter(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
        takenAt: String,
        photoId: String,
        limit: Int,
    ): List<PhotoEntity>

    /** See `PhotoDao.pageBefore` for the inner ascending query. */
    @Query(
        "SELECT * FROM (SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED " +
            "AND (r.takenAt > :takenAt OR (r.takenAt = :takenAt AND r.photoId > :photoId)) " +
            "ORDER BY r.takenAt ASC, r.photoId ASC LIMIT :limit) ORDER BY takenAt DESC, photoId DESC",
    )
    suspend fun mergedBefore(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
        takenAt: String,
        photoId: String,
        limit: Int,
    ): List<PhotoEntity>

    /** The whole merged view, newest first, live: the detail pager's swipe list over a
     * filter (step 7). Grows as pages land, like the grid. */
    @Query("SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED$NEWEST_FIRST")
    fun observeMerged(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
    ): Flow<List<PhotoEntity>>

    /** The number of distinct photos the merged view exposes. */
    @Query("SELECT COUNT(*) FROM facet_stream_rows r WHERE $MERGED")
    suspend fun mergedCount(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
    ): Int

    /** The merged view's photos per local day: the partial rail's loaded histogram (F2). */
    @Query("SELECT r.localDay AS day, COUNT(*) AS n FROM facet_stream_rows r WHERE $MERGED GROUP BY r.localDay")
    fun observeMergedDays(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
    ): Flow<List<FacetDayCount>>

    /** A local jump's landing: the newest exposed photo on [day] or before it. */
    @Query("SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED AND r.localDay <= :day$NEWEST_FIRST LIMIT 1")
    suspend fun mergedLandingOnOrBefore(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
        day: String,
    ): PhotoEntity?

    /** The oldest exposed photo. */
    @Query("SELECT $COLUMNS FROM facet_stream_rows r WHERE $MERGED ORDER BY r.takenAt ASC, r.photoId ASC LIMIT 1")
    suspend fun mergedOldest(
        streams: Collection<String>,
        wTakenAt: String?,
        wPhotoId: String?,
    ): PhotoEntity?
}
