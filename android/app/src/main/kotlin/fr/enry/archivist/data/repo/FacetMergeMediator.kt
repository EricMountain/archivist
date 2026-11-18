package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineKey

/**
 * Timeline place filter F1: the network half of the filtered timeline's `Pager`, over
 * the session cache in `facet_streams`/`facet_stream_rows`. A k-way merge of the selected
 * facet streams that fetches **only when the grid asks** (owner, 2026-11-17: load as you
 * scroll, never bulk):
 *
 * * **REFRESH** fetches the first page of each selected stream that has none yet, and
 *   nothing else. A stream cached from an earlier keystroke costs nothing.
 * * **APPEND** fetches the next page of the stream(s) holding the watermark — the
 *   unfinished stream whose oldest fetched row is newest, which is what stops the merged
 *   view from exposing more — until the view exposes at least one more photo.
 * * **PREPEND** has nothing to do: newest-first streams have nothing above their first
 *   page.
 *
 * The fetches themselves run in [PlaceFilterRepository]'s own scope, deduplicated per
 * stream, so a fetch the next selection still needs survives this pager being replaced
 * (F6); see [PlaceFilterRepository.retain].
 */
@OptIn(ExperimentalPagingApi::class)
class FacetMergeMediator internal constructor(
    private val streams: List<FacetStreamSpec>,
    private val repository: PlaceFilterRepository,
) : RemoteMediator<TimelineKey, PhotoEntity>() {
    override suspend fun load(
        loadType: LoadType,
        state: PagingState<TimelineKey, PhotoEntity>,
    ): MediatorResult =
        when (loadType) {
            LoadType.REFRESH -> repository.loadFirstPages(streams)
            LoadType.APPEND -> repository.loadAtWatermark(streams)
            LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
        }
}
