package fr.enry.archivist.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Transactor
import androidx.room.useWriterConnection
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.local.db.TimelineCursorEntity
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.TimelineEntryDto
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Plan step 2.11: "network fills Room, Room feeds the pager" (android.md). The `Int`
 * paging key [PagingState] itself works with is Room's own row-offset key (from the
 * generated `PhotoDao.pagingSource()`) — unrelated to `GET /photos`'s opaque cursor
 * string, which this class tracks separately via [TimelineCursorEntity], the same
 * decoupling the "network + database" `RemoteMediator` recipe always uses.
 *
 * `REFRESH` always re-fetches page one and clears everything else — the standard
 * recipe, and safe here because it only touches the cache, never anything server-side.
 * Offline-first falls out of the architecture rather than needing its own code: a
 * `REFRESH` attempted with no network throws before this ever touches Room, so
 * whatever was already cached from a previous session stays exactly as it was, and the
 * `PagingSource` above keeps serving it directly from Room regardless of whether this
 * mediator's own fetch just failed.
 */
@OptIn(ExperimentalPagingApi::class)
class TimelineRemoteMediator(
    private val instanceStore: InstanceStore,
    private val archivistApiFactory: ArchivistApiFactory,
    private val db: AppDatabase,
) : RemoteMediator<Int, PhotoEntity>() {
    private val photoDao = db.photoDao()
    private val timelineCursorDao = db.timelineCursorDao()

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PhotoEntity>,
    ): MediatorResult {
        return try {
            val cursor =
                when (loadType) {
                    LoadType.REFRESH -> null
                    LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                    LoadType.APPEND -> {
                        timelineCursorDao.observe().first()?.cursor
                            ?: return MediatorResult.Success(endOfPaginationReached = true)
                    }
                }

            val instance = instanceStore.current.first() ?: return MediatorResult.Error(IllegalStateException("no connected instance"))
            val api = apiFor(instance)
            val response =
                api.getPhotos(photosUrl(instance.document.apiBase), cursor = cursor, limit = state.config.pageSize)

            // Not androidx.room.withTransaction: that extension still routes through the
            // legacy SupportSQLiteOpenHelper-based transaction API, which a
            // setDriver(...)-configured database (see TestDatabase.kt) has none of --
            // useWriterConnection/Transactor is the driver-based replacement, and works
            // identically against a framework-backed database too (this repo's real one).
            db.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    if (loadType == LoadType.REFRESH) {
                        photoDao.clear()
                    }
                    photoDao.upsertAll(response.items.map { it.toEntity() })
                    if (response.cursor != null) {
                        timelineCursorDao.set(TimelineCursorEntity(cursor = response.cursor, updatedAt = nowIso()))
                    } else {
                        timelineCursorDao.clear()
                    }
                }
            }

            MediatorResult.Success(endOfPaginationReached = response.cursor == null)
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }

    private fun apiFor(instance: StoredInstance): ArchivistApi =
        archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
}

private fun photosUrl(apiBase: String) = "$apiBase/photos"

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** `thumbs`' JSON-object keys arrive as strings (`"256"`) even though the server's own
 * `ThumbMap` is keyed by number — see [TimelineEntryDto]'s own doc. */
internal fun TimelineEntryDto.toEntity(): PhotoEntity =
    PhotoEntity(
        photoId = photoId,
        takenAt = takenAt,
        tzOffsetMin = tzOffsetMin,
        mime = mime,
        width = width,
        height = height,
        status = AssetStatus.valueOf(status.uppercase()),
        thumbs = thumbs.mapKeys { it.key.toInt() },
        encDek = encDek,
        encKeyId = encKeyId,
    )
