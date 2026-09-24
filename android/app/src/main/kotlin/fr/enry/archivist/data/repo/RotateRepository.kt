package fr.enry.archivist.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.ContentHash
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.ImageLockedException
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PostPhotoThumbsRequest
import fr.enry.archivist.data.remote.PostRenditionReplaceRequest
import fr.enry.archivist.data.remote.ThumbDescriptorDto
import fr.enry.archivist.sync.Thumbnailer
import fr.enry.archivist.sync.encodeWebp
import fr.enry.archivist.sync.targetDimensions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** What [RotateRepository.rotate] produced, for
 * [fr.enry.archivist.ui.detail.DetailViewModel] to act on. */
sealed interface RotateOutcome {
    data object Done : RotateOutcome

    data class Error(val message: String) : RotateOutcome
}

/** Above this, encrypting and re-uploading a rotated original in one shot (this app has
 * no streaming-mode upload path — [fr.enry.archivist.data.repo.UploadRepository] is the
 * only other whole-object-vs-streaming decision point, and that one exists precisely
 * because the upload worker *does* stream) isn't attempted; refused with a clear error
 * instead of either failing obscurely mid-request or holding tens of MB of ciphertext in
 * memory for a feature meant for an ordinary photo. */
private const val MAX_ROTATABLE_BYTES = 33_554_432L

/** The three offsets the "Rotate" submenu offers, each carrying the clockwise degrees
 * [RotateRepository.rotate] actually applies — [Matrix.postRotate]'s own convention, the
 * opposite sign from Pillow's (see `modify_media.py`'s `--rotate`), which is why
 * counter-clockwise is 270 here rather than -90. */
enum class RotateDirection(val clockwiseDegrees: Int) {
    CLOCKWISE_90(90),
    COUNTERCLOCKWISE_90(270),
    ROTATE_180(180),
}

/**
 * The "Rotate" submenu's actions — decodes the primary rendition's original at full
 * resolution (never Thumbnailer's own decode path, which intentionally downsamples to
 * thumbnail size), rotates it by the requested [RotateDirection], and both replaces the
 * stored original (`POST .../renditions/{id}/replace`) and regenerates the thumbnail
 * ladder from those same rotated pixels (`POST .../thumbs`) — the in-app counterpart to
 * `modify_media.py orientation --rotate`. Source resolution (local file first, server
 * download as fallback) mirrors [RepairRepository] exactly; the two differ in what they
 * do with the decoded bitmap, not in how they find it.
 */
@Singleton
class RotateRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val uploadQueueDao: UploadQueueDao,
        private val photoDao: PhotoDao,
        private val masterKeyHolder: MasterKeyHolder,
        private val enrolmentRepository: EnrolmentRepository,
        private val photoDetailRepository: PhotoDetailRepository,
        private val jumpCoordinator: TimelineJumpCoordinator,
        private val okHttpClient: OkHttpClient,
        @ApplicationContext private val context: Context,
    ) {
        suspend fun rotate(
            detail: PhotoDetail,
            direction: RotateDirection,
        ): RotateOutcome {
            val masterKey = masterKeyHolder.current.value ?: return RotateOutcome.Error("locked — unlock to rotate")
            val hashSecret =
                enrolmentRepository.ensureHashSecret().getOrElse {
                    return RotateOutcome.Error("locked — unlock to rotate")
                }
            val rendition =
                detail.renditions.find { it.renditionId == detail.primaryRend } ?: detail.renditions.firstOrNull()
                    ?: return RotateOutcome.Error("this asset has no rendition to rotate")

            val instance = instanceStore.current.first() ?: return RotateOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val apiBase = instance.document.apiBase

            var tempFile: File? = null
            var base: Bitmap? = null
            var rotated: Bitmap? = null
            return try {
                val dek = masterKey.unwrapDek(decode(detail.encDek))

                val localRow =
                    uploadQueueDao.getByPhotoId(detail.photoId)
                        .find { it.state == UploadState.DONE && it.renditionId == rendition.renditionId && it.mime != null }
                val (sourceUri, resolvedTemp) = resolveSourceUri(detail.photoId, detail.encDek, rendition, localRow)
                tempFile = resolvedTemp

                base = decodeFullBitmap(sourceUri)
                rotated = rotateBy(base!!, direction.clockwiseDegrees)
                val plainBytes = encodeBitmap(rotated!!, rendition.mime)
                if (plainBytes.size > MAX_ROTATABLE_BYTES) {
                    return RotateOutcome.Error("this photo is too large to rotate in-app yet")
                }

                val contentHash = ContentHash.of(hashSecret, ByteArrayInputStream(plainBytes))
                val originalIv = EnvelopeCrypto.generateIv()
                val originalCiphertext =
                    WholeObjectCipher.encrypt(dek, originalIv, Aad.of(detail.photoId, ObjectRef.Rendition(rendition.renditionId)), plainBytes)

                val replaceResponse =
                    api.postRenditionReplace(
                        replaceUrl(apiBase, detail.photoId, rendition.renditionId),
                        PostRenditionReplaceRequest(
                            contentHash = contentHash,
                            plainBytes = plainBytes.size.toLong(),
                            bytes = originalCiphertext.size.toLong(),
                            mime = rendition.mime,
                            width = rotated!!.width,
                            height = rotated!!.height,
                            encIv = encode(originalIv),
                            encChunkSize = 0,
                        ),
                    )
                if (!replaceResponse.isSuccessful) {
                    return RotateOutcome.Error("server rejected the rotation (HTTP ${replaceResponse.code()})")
                }
                val uploadUrl = replaceResponse.body()?.uploadUrl ?: return RotateOutcome.Error("empty response body")
                putBytes(uploadUrl, rendition.mime, originalCiphertext)

                // Best-effort: the original is already correctly rotated at this point, so a
                // thumbnail-repair failure here is reported but doesn't undo it — the user can
                // always re-run "Repair thumbnails" separately, same as any other stale-thumbnail
                // case RepairRepository already handles.
                val thumbWarning = reuploadThumbnails(api, apiBase, detail.photoId, dek, rotated!!)

                val refreshed = api.getPhotoAsTimelineEntry(photoUrl(apiBase, detail.photoId))
                jumpCoordinator.stageExternalRefreshKey(TimelineKey(refreshed.meta.takenAt, refreshed.meta.photoId))
                photoDao.upsertAll(listOf(refreshed.meta.toEntity()))

                if (thumbWarning != null) RotateOutcome.Error(thumbWarning) else RotateOutcome.Done
            } catch (e: ImageLockedException) {
                RotateOutcome.Error("locked — unlock to rotate")
            } catch (e: IOException) {
                RotateOutcome.Error(e.message ?: "network error")
            } catch (e: HttpException) {
                RotateOutcome.Error("HTTP ${e.code()}")
            } catch (e: Exception) {
                RotateOutcome.Error(e.message ?: (e::class.simpleName ?: "couldn't rotate this photo"))
            } finally {
                rotated?.recycle()
                if (base !== rotated) base?.recycle()
                tempFile?.delete()
            }
        }

        /** Local first ([UploadQueueDao], same table [RepairRepository]/[DeleteRepository]
         * already use to map a photoId back to the device file it came from); the
         * already-uploaded original downloaded and decrypted otherwise, into a `cacheDir`
         * temp file the caller must delete — same "decrypted plaintext never outlives this
         * operation on disk" rule [fr.enry.archivist.ui.detail.VideoPlayer] and
         * [RepairRepository] both follow. Unlike [RepairRepository], this checks the local
         * file actually opens *before* committing to it (there's no [fr.enry.archivist.sync.Thumbnailer]
         * call downstream whose [IOException] would otherwise be this class's only signal
         * that the row was stale). */
        private suspend fun resolveSourceUri(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
            localRow: UploadQueueEntity?,
        ): Pair<String, File?> {
            if (localRow != null) {
                val opens =
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(localRow.localUri))?.close()
                    }.isSuccess
                if (opens) return localRow.localUri to null
            }
            val bytes = photoDetailRepository.downloadOriginal(photoId, encDek, rendition)
            val file = File.createTempFile("rotate-", ".${rendition.ext}", context.cacheDir)
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            return Uri.fromFile(file).toString() to file
        }

        /** Full resolution, deliberately not [fr.enry.archivist.sync.Thumbnailer]'s own
         * decode path — that one samples down to the largest thumbnail rung on purpose.
         * [ImageDecoder], unlike the legacy `BitmapFactory` [fr.enry.archivist.ui.detail.DetailScreen]'s
         * "view original" overlay has to work around by hand, already applies EXIF
         * orientation itself. */
        private fun decodeFullBitmap(sourceUri: String): Bitmap {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(sourceUri))
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        }

        private fun rotateBy(
            base: Bitmap,
            clockwiseDegrees: Int,
        ): Bitmap {
            val matrix = Matrix().apply { postRotate(clockwiseDegrees.toFloat()) }
            return Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        }

        private fun encodeBitmap(
            bitmap: Bitmap,
            mime: String,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val format =
                when (mime) {
                    "image/png" -> Bitmap.CompressFormat.PNG
                    "image/webp" ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                    // Covers image/jpeg and anything else Bitmap.compress will attempt —
                    // Bitmap has no encoder for RAW/HEIC, so a rendition in one of those
                    // formats fails here with a clear exception rather than silently
                    // mis-encoding, same as ImageDecoder already refusing to decode one.
                    else -> Bitmap.CompressFormat.JPEG
                }
            val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
            bitmap.compress(format, quality, out)
            return out.toByteArray()
        }

        /** Returns a warning message on failure, null on success — deliberately not an
         * exception: a thumbnail-repair failure after the original already replaced
         * successfully shouldn't unwind and report the whole rotation as failed. */
        private suspend fun reuploadThumbnails(
            api: ArchivistApi,
            apiBase: String,
            photoId: String,
            dek: ByteArray,
            rotated: Bitmap,
        ): String? {
            return try {
                val rungs =
                    Thumbnailer.SIZES.map { longestEdge ->
                        val (w, h) = targetDimensions(rotated.width, rotated.height, longestEdge)
                        val scaled = if (w == rotated.width && h == rotated.height) rotated else Bitmap.createScaledBitmap(rotated, w, h, true)
                        try {
                            Triple(longestEdge, encodeWebp(scaled), Unit)
                        } finally {
                            if (scaled !== rotated) scaled.recycle()
                        }
                    }
                val encrypted =
                    rungs.map { (size, bytes, _) ->
                        val iv = EnvelopeCrypto.generateIv()
                        val ciphertext = WholeObjectCipher.encrypt(dek, iv, Aad.of(photoId, ObjectRef.Thumbnail(size)), bytes)
                        Triple(size, iv, ciphertext)
                    }
                val descriptors =
                    encrypted.associate { (size, iv, ciphertext) -> size.toString() to ThumbDescriptorDto(ciphertext.size.toLong(), encode(iv)) }
                val response = api.postPhotoThumbs(thumbsUrl(apiBase, photoId), PostPhotoThumbsRequest(descriptors))
                if (!response.isSuccessful) return "rotated, but couldn't repair thumbnails (HTTP ${response.code()})"
                val body = response.body() ?: return "rotated, but the thumbnail repair response was empty"
                for ((size, _, ciphertext) in encrypted) {
                    val url = body.thumbUploads[size.toString()] ?: continue
                    putBytes(url, "image/webp", ciphertext)
                }
                null
            } catch (e: Exception) {
                "rotated, but couldn't repair thumbnails: ${e.message ?: e::class.simpleName}"
            }
        }

        private suspend fun putBytes(
            url: String,
            contentType: String,
            bytes: ByteArray,
        ) {
            withContext(Dispatchers.IO) {
                val body = bytes.toRequestBody(contentType.toMediaTypeOrNull())
                val request = Request.Builder().url(url).put(body).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("PUT to $url failed: HTTP ${resp.code}")
                }
            }
        }
    }

private fun replaceUrl(
    apiBase: String,
    photoId: String,
    renditionId: String,
) = "$apiBase/photos/$photoId/renditions/$renditionId/replace"

private fun thumbsUrl(
    apiBase: String,
    photoId: String,
) = "$apiBase/photos/$photoId/thumbs"

private fun photoUrl(
    apiBase: String,
    photoId: String,
) = "$apiBase/photos/$photoId"

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
