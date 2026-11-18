package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.local.db.AssetStatus
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.FacetEntryDto
import fr.enry.archivist.data.remote.PlacesResponse
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

private const val HTTP_NOT_MODIFIED = 304

/**
 * Geolocation plan step 8: the place vocabulary, `GET /places`, cached in Room with its
 * `ETag` and revalidated with `If-None-Match` — the same mechanism as
 * [PhotoRepository.syncHistogram], for the same reasons (see
 * [fr.enry.archivist.data.local.db.PlaceVocabularyEntity]). Place search reads
 * [vocabulary], so it works offline from whatever was last cached.
 *
 * The photos of the chosen places (`GET /facets/{type}/{value}`) go through
 * [PlaceFilterRepository]'s stream cache (timeline place filter), which shares [facetUrl]
 * and [toEntity] from here.
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
