package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.FacetStreamEntity
import fr.enry.archivist.data.local.db.FacetStreamRowEntity
import fr.enry.archivist.data.local.db.ThumbEntry
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/** A [PlaceFilterRepository] over [db] for the repositories that patch its cached rows
 * (place filter step 7). It never fetches: these tests seed the rows themselves. */
internal fun placeFilterFor(db: AppDatabase): PlaceFilterRepository =
    PlaceFilterRepository(
        db,
        { _, _, _, _ -> throw IOException("no facet fetches in this test") },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

/** Starts a filter session the way the app does, by collecting a pager once (which also
 * empties the cache, as a cold start's first pager does). Seed rows after this. */
internal suspend fun PlaceFilterRepository.startFilterSessionForTest() {
    pager(listOf(FacetStreamSpec("COUNTRY", "ZZ", "ZZ", null, null))).first()
}

/** One cached filter row for [photoId] in [streamKey] (a finished stream, so nothing is
 * cut off by a watermark), with the stream's place columns (F12). */
internal suspend fun AppDatabase.seedFilterRow(
    streamKey: String,
    photoId: String,
    takenAt: String = "2026-08-30T10:00:00.000Z",
    tzOffsetMin: Int = 0,
    placeCc: String? = null,
    placeName: String? = null,
    placeKey: String? = null,
    specificity: Int = 2,
    thumbs: Map<Int, ThumbEntry> = emptyMap(),
    localDay: String = takenAt.take(10),
) {
    val dao = facetStreamDao()
    dao.upsertRows(
        listOf(
            FacetStreamRowEntity(
                streamKey = streamKey,
                photoId = photoId,
                takenAt = takenAt,
                tzOffsetMin = tzOffsetMin,
                mime = "image/jpeg",
                width = 100,
                height = 100,
                status = AssetStatus.READY,
                thumbs = thumbs,
                encDek = "dek",
                encKeyId = "mk-1",
                placeCc = placeCc,
                placeName = placeName,
                placeKey = placeKey,
                specificity = specificity,
                localDay = localDay,
            ),
        ),
    )
    if (dao.stream(streamKey) == null) {
        dao.upsertStream(FacetStreamEntity(streamKey, cursor = null, oldestTakenAt = takenAt, oldestPhotoId = photoId, done = true, fetchedAt = takenAt))
    }
}
