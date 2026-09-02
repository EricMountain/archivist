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

/** Server's own `MAX_LIMIT` (`routes/photos.ts`) is 200; this is a client-side choice
 * well under that, sized for grid smoothness rather than round-trip count. */
private const val TIMELINE_PAGE_SIZE = 60

/**
 * Plan step 2.11: the `Pager` wiring behind the timeline grid. `PhotoDao.pagingSource()`
 * is Room's own generated `PagingSource` — the `Int` key it works with, and
 * [TimelineRemoteMediator]'s own `GET /photos` cursor, are two separate things; see
 * that class's doc.
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
                pagingSourceFactory = { db.photoDao().pagingSource() },
            ).flow

        /** Plan step 2.12: the plain (non-`Paging`) mirror of [timeline]'s own ordering,
         * for the detail screen's swipe-between-photos — index navigation over
         * `LazyPagingItems` doesn't compose cleanly with `HorizontalPager` once headers
         * are mixed in (see `TimelineScreen`'s grid), so the detail screen paginates
         * over this list directly instead. Same source, same order, just not chunked. */
        fun observeTimeline(): Flow<List<PhotoEntity>> = db.photoDao().observeTimeline()
    }
