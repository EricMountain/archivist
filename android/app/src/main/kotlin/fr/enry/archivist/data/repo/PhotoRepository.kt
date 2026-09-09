package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.remote.ArchivistApiFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Server's own `MAX_LIMIT` (`routes/photos.ts`) is 200; this is a client-side choice
 * well under that, sized for grid smoothness rather than round-trip count. */
private const val TIMELINE_PAGE_SIZE = 60

/**
 * Plan step 2.11: the `Pager` wiring behind the timeline grid. [TimelinePagingSource]'s
 * own `TimelineKey` and [TimelineRemoteMediator]'s `GET /photos` cursor are two separate
 * things — see that class's doc.
 */
@Singleton
class PhotoRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
    ) {
        @OptIn(ExperimentalPagingApi::class)
        fun timeline(): Flow<PagingData<PhotoEntity>> =
            Pager(
                config = PagingConfig(pageSize = TIMELINE_PAGE_SIZE, enablePlaceholders = false),
                remoteMediator = TimelineRemoteMediator(instanceStore, archivistApiFactory, db),
                pagingSourceFactory = { TimelinePagingSource(db, db.photoDao()) },
            ).flow

        /** Plan step 2.12: the plain (non-`Paging`) mirror of [timeline]'s own ordering,
         * for the detail screen's swipe-between-photos — index navigation over
         * `LazyPagingItems` doesn't compose cleanly with `HorizontalPager` once headers
         * are mixed in (see `TimelineScreen`'s grid), so the detail screen paginates
         * over this list directly instead. Same source, same order, just not chunked. */
        fun observeTimeline(): Flow<List<PhotoEntity>> = db.photoDao().observeTimeline()

        /** Called once per finished upload (see [UploadEvents]'s own doc) to fold the
         * newest server-known photos into the local cache. Deliberately *not*
         * `LazyPagingItems.refresh()`: that goes through [TimelineRemoteMediator]'s
         * `REFRESH` branch, which clears the whole `photos` table before repopulating
         * page one — fine for a cold start or an explicit pull-to-refresh (both start
         * the user at the top already), but it briefly deletes every older, already
         * -loaded page out from under whatever the user is currently scrolled through,
         * which is what made the grid visibly jump back to the top on every upload. A
         * plain upsert instead lets Room's own `PagingSource` invalidation (which
         * `pagingSourceFactory` already reacts to) slot the new photo in without
         * disturbing anything else or re-entering the `RemoteMediator` at all. */
        suspend fun refreshLatest() {
            val instance = instanceStore.current.first() ?: return
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val response = api.getPhotos(photosUrl(instance.document.apiBase), cursor = null, limit = TIMELINE_PAGE_SIZE)
            db.photoDao().upsertAll(response.items.map { it.toEntity() })
        }
    }
