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
    }
