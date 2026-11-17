package fr.enry.archivist.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.PlacesResponse
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** The server's own `MAX_LIMIT` for a facet page (`routes/facets.ts`) is 200; one grid
 * page is a client-side choice under it, matching the timeline's. */
private const val FACET_PAGE_SIZE = 60

private const val FACET_MAX_LIMIT = 200

private const val HTTP_NOT_MODIFIED = 304

/**
 * Geolocation plan step 8: place search's two server calls.
 *
 * 1. The place vocabulary, `GET /places`, cached in Room with its `ETag` and revalidated
 *    with `If-None-Match` — the same mechanism as [PhotoRepository.syncHistogram], for the
 *    same reasons (see [fr.enry.archivist.data.local.db.PlaceVocabularyEntity]). Search
 *    reads [vocabulary], so it works offline from whatever was last cached.
 * 2. The photos for one chosen place, `GET /facets/{type}/{value}`, through a plain
 *    [PagingSource] with no Room cache: a result list is short-lived and re-fetched per
 *    search, unlike the timeline.
 */
@Singleton
class PlaceRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val json: Json,
    ) {
        /** The cached vocabulary, or null when nothing has been fetched yet. A row that no
         * longer decodes (it shouldn't: the shape is versioned with the app) reads as none,
         * so the next sync replaces it. */
        fun vocabulary(): Flow<PlacesResponse?> =
            db.placeVocabularyDao().observe().map { row ->
                row?.let {
                    try {
                        json.decodeFromString(PlacesResponse.serializer(), it.body)
                    } catch (_: SerializationException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }

        /**
         * Revalidates the cached vocabulary, replaying its `ETag` as `If-None-Match`. A 304
         * leaves the row alone. Throws on a network or HTTP failure, unlike
         * [PhotoRepository.syncHistogram]'s callers' silence, so the search screen can tell
         * "offline with nothing cached" apart from "no places yet".
         */
        suspend fun syncVocabulary() {
            val instance = instanceStore.current.first() ?: return
            val dao = db.placeVocabularyDao()
            val cached = dao.get()
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val response = api.getPlaces(placesUrl(instance.document.apiBase), cached?.etag)
            if (response.code() == HTTP_NOT_MODIFIED) return
            if (!response.isSuccessful) throw HttpException(response)
            val body = response.body() ?: return
            dao.set(json.encodeToString(PlacesResponse.serializer(), body), response.headers()["etag"])
        }

        /** Drops the cached vocabulary's `ETag` (keeping its body for offline search), so the
         * next [syncVocabulary] is an unconditional fetch. Called after this device changed a
         * photo's place (geolocation plan step 9). */
        suspend fun invalidateVocabulary() {
            db.placeVocabularyDao().dropEtag()
        }

        /** The photos carrying facet [type] = [value] (`COUNTRY`/`REGION`/`PLACE`), newest
         * first. Empty when no instance is connected. */
        fun facetPhotos(
            type: String,
            value: String,
        ): Flow<PagingData<PhotoEntity>> =
            flow {
                val instance = instanceStore.current.first()
                if (instance == null) {
                    emit(PagingData.empty())
                    return@flow
                }
                val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
                val url = facetUrl(instance.document.apiBase, type, value)
                emitAll(
                    Pager(
                        config = PagingConfig(pageSize = FACET_PAGE_SIZE, enablePlaceholders = false),
                        pagingSourceFactory = { FacetPagingSource(api, url) },
                    ).flow,
                )
            }
    }

/**
 * `GET /facets/{type}/{value}` as a [PagingSource]. The key is the server's opaque
 * cursor (null for the first page); there is no `prevKey` because results only ever
 * grow downward from the newest. A refresh restarts from the top: the cursor can't be
 * rebuilt for an arbitrary position, and a result list is short enough that this is fine.
 */
internal class FacetPagingSource(
    private val api: ArchivistApi,
    private val url: String,
) : PagingSource<String, PhotoEntity>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, PhotoEntity> =
        try {
            val page = api.getFacetPage(url, cursor = params.key, limit = params.loadSize.coerceAtMost(FACET_MAX_LIMIT))
            LoadResult.Page(
                data = page.items.map { it.toEntity() },
                prevKey = null,
                nextKey = page.cursor,
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: HttpException) {
            LoadResult.Error(e)
        } catch (e: IllegalArgumentException) {
            // An unexpected body (`SerializationException` is one) or a status
            // `AssetStatus.valueOf` doesn't know: an error state, not an app crash. Before
            // this catch, a facet body missing a required field killed the app.
            LoadResult.Error(e)
        }

    override fun getRefreshKey(state: PagingState<String, PhotoEntity>): String? = null
}

/** A facet entry as the grid cell's [PhotoEntity], the same mapping as
 * [fr.enry.archivist.data.remote.TimelineEntryDto]'s `toEntity()`. Facet items carry no
 * `preview` and no `place`, so neither is set. */
internal fun FacetEntryDto.toEntity(): PhotoEntity =
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

internal fun placesUrl(apiBase: String) = "$apiBase/places"

/** [value] is percent-encoded as one path segment. The ids place search sends (`JP`,
 * `JP.22`, `1857910`) need no escaping, but a future facet value might. */
internal fun facetUrl(
    apiBase: String,
    type: String,
    value: String,
) = "$apiBase/facets/$type/${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
