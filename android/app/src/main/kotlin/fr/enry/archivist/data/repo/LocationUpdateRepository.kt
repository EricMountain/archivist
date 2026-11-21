package fr.enry.archivist.data.repo

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.enry.archivist.crypto.ImageLockedException
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PlaceDto
import fr.enry.archivist.data.remote.PutLocationRequest
import fr.enry.archivist.data.remote.TransactionConflictRetry
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.domain.ExifExtractor
import fr.enry.archivist.domain.GeoPoint
import fr.enry.archivist.domain.Iso6709
import fr.enry.archivist.sync.VideoLocationReader
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** What [LocationUpdateRepository.updateLocation] did, for
 * [fr.enry.archivist.ui.detail.DetailViewModel]'s status line. */
sealed interface LocationUpdateOutcome {
    /** Coordinates were found and sent. [detail] is the refreshed detail; its `place` may
     * still be null when the coordinates are nowhere near a named place (open sea). */
    data class Updated(val detail: PhotoDetail) : LocationUpdateOutcome

    /** No coordinates in the blob or the original: the place was cleared (`place: null`). */
    data class NoLocation(val detail: PhotoDetail) : LocationUpdateOutcome

    data class Error(val message: String) : LocationUpdateOutcome
}

private const val HTTP_CONFLICT = 409

/**
 * Geolocation plan step 9, the detail screen's "Update location": the procedure in
 * geolocation.md "Manual updates" (design.md "Location", "Manual update") for one photo.
 *
 * 1. The coordinates in the decrypted `ExifBlob`, if any.
 * 2. Otherwise the primary rendition's original, fetched and decrypted to a `cacheDir`
 *    temp file by the same streaming path as "View original" ([OriginalDownloader]), and
 *    read there: `ExifInterface` for a still, [VideoLocationReader] for a video. (0, 0)
 *    is no location ([GeoPoint.of]). The temp file is always deleted.
 * 3. Geocoded with the bundled gazetteer plus the instance's region packs ([PlaceGeocoder]).
 * 4. `PUT /photos/{photoId}/location`. When the coordinates came from the original, the
 *    request also carries the blob re-encrypted with `gpsLat`/`gpsLon`/`gpsAltM` added and
 *    every other field kept ([ExifCrypto], so exactly as upload encrypts it: same DEK, fresh
 *    IV, same AAD), so the next update needn't download the original again.
 * 5. No coordinates anywhere: `place: null`, clearing a stale place.
 *
 * A 409 (the place or trash state moved under the server's transaction) is retried once
 * from a freshly fetched detail; the original is not downloaded a second time. On success
 * the Room row's place columns are updated (so the timeline's date bubble follows) and the
 * cached place vocabulary's `ETag` is dropped, so search refetches it on next open.
 *
 * Strip-on wins: with `stripLocationOnUpload` on, [isAvailable] is false (the menu item is
 * hidden) and [updateLocation] refuses.
 */
@Singleton
class LocationUpdateRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val masterKeyHolder: MasterKeyHolder,
        private val photoDetailRepository: PhotoDetailRepository,
        private val originalDownloader: OriginalDownloader,
        private val ownerSettingsRepository: OwnerSettingsRepository,
        private val placeGeocoder: PlaceGeocoder,
        private val videoLocationReader: VideoLocationReader,
        private val photoDao: PhotoDao,
        private val placeRepository: PlaceRepository,
        private val placeFilterRepository: PlaceFilterRepository,
        @ApplicationContext private val context: Context,
    ) {
        /** False when the owner's `stripLocationOnUpload` is on. Read the way upload reads
         * it: a failed read counts as off. */
        suspend fun isAvailable(): Boolean = !stripLocationOn()

        private suspend fun stripLocationOn(): Boolean = ownerSettingsRepository.stripLocationOnUpload().getOrDefault(false)

        suspend fun updateLocation(detail: PhotoDetail): LocationUpdateOutcome =
            try {
                run(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageLockedException) {
                LocationUpdateOutcome.Error("Unlock the app to update the location.")
            } catch (e: HttpException) {
                LocationUpdateOutcome.Error("HTTP ${e.code()}")
            } catch (e: IOException) {
                LocationUpdateOutcome.Error(e.message ?: "network error")
            }

        private suspend fun run(initial: PhotoDetail): LocationUpdateOutcome {
            if (stripLocationOn()) return LocationUpdateOutcome.Error("Location is turned off for this library.")
            val instance = instanceStore.current.first() ?: return LocationUpdateOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val url = "${instance.document.apiBase}/photos/${initial.photoId}/location"

            var detail = initial
            // The original is read at most once, even across the 409 retry.
            var originalRead = false
            var fromOriginal: GeoPoint? = null
            for (attempt in 1..2) {
                // A blob that exists but couldn't be decrypted can't be re-encrypted
                // without losing its other fields, so stop rather than overwrite it.
                if (detail.exifDecryptFailed) return LocationUpdateOutcome.Error("Couldn't decrypt this photo's metadata.")
                val inBlob = detail.exif?.let { coordinates(it) }
                if (inBlob == null && !originalRead) {
                    fromOriginal = readOriginal(detail)
                    originalRead = true
                }
                val point = inBlob ?: fromOriginal
                val place = point?.let { geocode(it) }
                val newExif = if (inBlob == null && point != null) reencrypt(detail, point) else null

                // A 409 TRANSACTION_CONFLICT (another write held a #PLACES counter row)
                // is retried as-is with backoff; only a plain 409 means the photo
                // itself changed and needs the refetch below.
                val body =
                    PutLocationRequest(
                        place = place,
                        exifEnc = newExif?.ciphertext?.let(::encode),
                        exifIv = newExif?.iv?.let(::encode),
                    )
                val attempted = TransactionConflictRetry.run { api.putPhotoLocation(url, body) }
                if (attempted.transactionConflict) return LocationUpdateOutcome.Error("The server was busy. Try again.")
                val response = attempted.response
                if (response.code() == HTTP_CONFLICT && attempt == 1) {
                    detail = photoDetailRepository.fetchDetail(detail.photoId)
                    continue
                }
                if (response.code() == HTTP_CONFLICT) {
                    return LocationUpdateOutcome.Error("The photo changed while updating. Try again.")
                }
                if (!response.isSuccessful) return LocationUpdateOutcome.Error("HTTP ${response.code()}")

                val stored = response.body()?.place
                // The same flattening as TimelineEntryDto.toEntity(): the locality, else
                // the region.
                val level = stored?.locality ?: stored?.region
                photoDao.setPlace(detail.photoId, stored?.cc, level?.name, level?.id)
                // The place filter's cached rows too (step 7; see patchPlace for a photo
                // that has left the filtered places).
                placeFilterRepository.patchPlace(detail.photoId, stored)
                placeRepository.invalidateVocabulary()

                val refreshed = refreshed(detail, stored, point, newExif != null)
                return if (point == null) {
                    LocationUpdateOutcome.NoLocation(refreshed)
                } else {
                    LocationUpdateOutcome.Updated(refreshed)
                }
            }
            error("unreachable")
        }

        /** The refetched detail; if that fails, the old one patched with what was sent,
         * so a successful update never shows as an error. */
        private suspend fun refreshed(
            detail: PhotoDetail,
            stored: PlaceDto?,
            point: GeoPoint?,
            exifChanged: Boolean,
        ): PhotoDetail =
            try {
                photoDetailRepository.fetchDetail(detail.photoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LocationUpdate", "refetching the detail failed; patching it locally", e)
                val exif = if (exifChanged && point != null) withCoordinates(detail.exif, point) else detail.exif
                detail.copy(place = stored, exif = exif)
            }

        /** The primary rendition's original, read for coordinates. Null when it has none or
         * can't be parsed. */
        private suspend fun readOriginal(detail: PhotoDetail): GeoPoint? {
            val rendition =
                detail.renditions.firstOrNull { it.renditionId == detail.primaryRend }
                    ?: detail.renditions.firstOrNull()
                    ?: return null
            context.cacheDir.mkdirs()
            val file =
                withContext(Dispatchers.IO) { File.createTempFile("locate-", ".${rendition.ext}", context.cacheDir) }
            try {
                originalDownloader.downloadOriginalToFile(detail.photoId, detail.encDek, rendition, file)
                return if (rendition.mime.startsWith("video/")) {
                    Iso6709.parse(videoLocationReader.read(file.toURI().toString(), file))
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            file.inputStream().use { ExifExtractor.extract(it).location }
                        } catch (e: IOException) {
                            // Not an image ExifInterface can parse (some RAW formats): no
                            // coordinates, rather than an error the user can't act on.
                            Log.w("LocationUpdate", "couldn't read the original's EXIF", e)
                            null
                        }
                    }
                }
            } finally {
                file.delete()
            }
        }

        private suspend fun geocode(point: GeoPoint): PlaceDto? =
            try {
                placeGeocoder.lookup(point.lat, point.lon)?.let(PlaceDto::forUpload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException("couldn't look up the place", e)
            }

        private fun reencrypt(
            detail: PhotoDetail,
            point: GeoPoint,
        ): EncryptedExif {
            val masterKey = masterKeyHolder.current.value ?: throw ImageLockedException()
            val dek = masterKey.unwrapDek(Base64.getDecoder().decode(detail.encDek))
            return ExifCrypto.encrypt(dek, detail.photoId, withCoordinates(detail.exif, point))
        }
    }

/** [blob]'s position, or null when it has none ([GeoPoint.of]'s rules, so (0, 0) is none). */
internal fun coordinates(blob: ExifBlob): GeoPoint? {
    val lat = blob.gpsLat ?: return null
    val lon = blob.gpsLon ?: return null
    return GeoPoint.of(lat, lon, blob.gpsAltM)
}

/** [blob] (or an empty one, for an asset uploaded with no EXIF at all) with [point]'s
 * coordinates; every other field kept. */
internal fun withCoordinates(
    blob: ExifBlob?,
    point: GeoPoint,
): ExifBlob = (blob ?: ExifBlob()).copy(gpsLat = point.lat, gpsLon = point.lon, gpsAltM = point.altM)

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationUpdateModule {
    @Binds
    abstract fun bindOriginalDownloader(impl: PhotoDetailRepository): OriginalDownloader
}
