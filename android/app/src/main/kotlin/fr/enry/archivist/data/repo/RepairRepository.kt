package fr.enry.archivist.data.repo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.ImageLockedException
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PostPhotoThumbsRequest
import fr.enry.archivist.data.remote.ThumbDescriptorDto
import fr.enry.archivist.sync.Thumbnail
import fr.enry.archivist.sync.Thumbnailer
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

/** What [RepairRepository.repairThumbnails] produced, for
 * [fr.enry.archivist.ui.detail.DetailViewModel] to act on. */
sealed interface RepairOutcome {
    data object Done : RepairOutcome

    /** The repair itself succeeded, but not from the local file: nothing usable was
     * found on this device (see [RepairRepository]'s own doc), so the primary
     * rendition's already-uploaded ciphertext was downloaded from the instance and
     * decrypted instead, exactly like "View original" does. Kept distinct from [Done]
     * — worth surfacing, since it's a sign this device's own bookkeeping of "which
     * local file produced this photo" was lost (app reinstall, the file itself moved
     * or was deleted outside this app, ...), which repairing a *different* photo will
     * likely hit again. */
    data class Warning(val message: String) : RepairOutcome

    data class Error(val message: String) : RepairOutcome
}

/**
 * The "repair a photo" menu action: regenerates and re-uploads an asset's thumbnail
 * ladder when it came out blank — a device that died mid-thumbnail, an
 * [android.graphics.ImageDecoder] hiccup on one particular file, or similar. Per
 * design.md's "Changing the ladder later", thumbnails are client-generated, so fixing
 * one requires a client that holds the plaintext — the server never does — but that
 * plaintext doesn't have to come from *this* local file specifically:
 *
 * 1. **Local first**: [UploadQueueDao.getByPhotoId] — the same table [DeleteRepository]
 *    already uses to map a `photoId` back to the local file(s) this device uploaded it
 *    from — is tried first, since it needs no network round trip beyond the repair
 *    upload itself.
 * 2. **Server fallback**: when that comes up empty, or the file it points at can no
 *    longer actually be opened (`IOException` from [Thumbnailer.generate]), the
 *    primary rendition's already-uploaded original is downloaded and decrypted via
 *    [PhotoDetailRepository.downloadOriginal] — the exact same "on demand, into a
 *    `cacheDir` temp file, deleted right after" pattern [fr.enry.archivist.ui.detail.VideoPlayer]
 *    already uses for playing a downloaded video original. This is squarely inside
 *    design.md's own model, not an exception to it: the *client* still generates the
 *    thumbnails from plaintext it holds, however briefly — the server is never asked
 *    to reprocess anything.
 *
 * [UploadQueueDao] rows going stale (case 1 failing while the file is still genuinely
 * on the device) is expected, not a bug to chase down per-occurrence: an Android app
 * reinstall (e.g. switching signing keys between a debug and a release build) wipes
 * Room — `upload_queue` included — while leaving `MediaStore`/the file itself
 * completely untouched, since neither is owned by this app's own data. The fallback
 * exists specifically so that common case still repairs cleanly instead of erroring.
 */
@Singleton
class RepairRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val uploadQueueDao: UploadQueueDao,
        private val photoDao: PhotoDao,
        private val thumbnailer: Thumbnailer,
        private val masterKeyHolder: MasterKeyHolder,
        private val photoDetailRepository: PhotoDetailRepository,
        private val okHttpClient: OkHttpClient,
        @ApplicationContext private val context: Context,
    ) {
        /** Regenerates from [PhotoDetail.primaryRend] — the same rendition the
         * grid/detail thumbnail is actually derived from server-side — falling back to
         * the first rendition listed when [PhotoDetail.primaryRend] is somehow absent. */
        suspend fun repairThumbnails(detail: PhotoDetail): RepairOutcome {
            val masterKey = masterKeyHolder.current.value ?: return RepairOutcome.Error("locked — unlock to repair")
            val rendition =
                detail.renditions.find { it.renditionId == detail.primaryRend } ?: detail.renditions.firstOrNull()
                    ?: return RepairOutcome.Error("this asset has no rendition to repair from")

            val instance = instanceStore.current.first() ?: return RepairOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val apiBase = instance.document.apiBase

            return try {
                val dek = masterKey.unwrapDek(decode(detail.encDek))

                val localRow =
                    uploadQueueDao.getByPhotoId(detail.photoId)
                        .find { it.state == UploadState.DONE && it.renditionId == rendition.renditionId && it.mime != null }

                var usedFallback = false
                val thumbnails =
                    if (localRow != null) {
                        try {
                            thumbnailer.generate(localRow.localUri, localRow.mime!!)
                        } catch (e: IOException) {
                            // The row exists, but the file it points at can no longer be
                            // opened (moved/deleted outside this app) -- fall through to
                            // the server copy exactly as if the row hadn't been found.
                            usedFallback = true
                            generateFromServer(detail.photoId, detail.encDek, rendition)
                        }
                    } else {
                        usedFallback = true
                        generateFromServer(detail.photoId, detail.encDek, rendition)
                    }

                val encrypted =
                    thumbnails.map { t ->
                        val iv = EnvelopeCrypto.generateIv()
                        val ciphertext =
                            WholeObjectCipher.encrypt(dek, iv, Aad.of(detail.photoId, ObjectRef.Thumbnail(t.longestEdge)), t.bytes)
                        EncryptedRepairThumb(t.longestEdge, iv, ciphertext)
                    }
                val descriptors =
                    encrypted.associate { t -> t.size.toString() to ThumbDescriptorDto(t.ciphertext.size.toLong(), encode(t.iv)) }

                val httpResponse = api.postPhotoThumbs(thumbsUrl(apiBase, detail.photoId), PostPhotoThumbsRequest(descriptors))
                if (!httpResponse.isSuccessful) return RepairOutcome.Error("server rejected repair (HTTP ${httpResponse.code()})")
                val body = httpResponse.body() ?: return RepairOutcome.Error("empty response body")

                for (t in encrypted) {
                    val url = body.thumbUploads[t.size.toString()] ?: continue
                    putBytes(url, t.ciphertext)
                }

                val refreshed = api.getPhotoAsTimelineEntry(photoUrl(apiBase, detail.photoId))
                photoDao.upsertAll(listOf(refreshed.meta.toEntity()))

                if (usedFallback) {
                    RepairOutcome.Warning(
                        "Repaired using the copy stored on the server — the original wasn't found on this device.",
                    )
                } else {
                    RepairOutcome.Done
                }
            } catch (e: ImageLockedException) {
                RepairOutcome.Error("locked — unlock to repair")
            } catch (e: IOException) {
                RepairOutcome.Error(e.message ?: "network error")
            } catch (e: HttpException) {
                RepairOutcome.Error("HTTP ${e.code()}")
            } catch (e: Exception) {
                RepairOutcome.Error(e.message ?: (e::class.simpleName ?: "couldn't regenerate the thumbnails"))
            }
        }

        /** Downloads and decrypts [rendition]'s already-uploaded original (same call
         * [fr.enry.archivist.ui.detail.DetailViewModel.viewOriginal] makes for "View
         * original") into a `cacheDir` temp file, thumbnails it, and deletes the temp
         * file immediately after — mirroring [fr.enry.archivist.ui.detail.VideoPlayer]'s
         * own "decrypted plaintext never outlives this operation on disk" rule. A
         * temp *file* rather than passing bytes directly: [Thumbnailer] only knows how
         * to decode a content URI ([android.graphics.ImageDecoder]/
         * [android.media.MediaMetadataRetriever] both need one), and writing a file is
         * cheaper than teaching it a second, byte-array-based decode path for what's
         * meant to be the uncommon fallback case. */
        private suspend fun generateFromServer(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
        ): List<Thumbnail> {
            val bytes = photoDetailRepository.downloadOriginal(photoId, encDek, rendition)
            val file = File.createTempFile("repair-", ".${rendition.ext}", context.cacheDir)
            return try {
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                thumbnailer.generate(Uri.fromFile(file).toString(), rendition.mime)
            } finally {
                file.delete()
            }
        }

        private suspend fun putBytes(
            url: String,
            bytes: ByteArray,
        ) {
            withContext(Dispatchers.IO) {
                val body = bytes.toRequestBody("image/webp".toMediaTypeOrNull())
                val request = Request.Builder().url(url).put(body).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("PUT to $url failed: HTTP ${resp.code}")
                }
            }
        }
    }

private class EncryptedRepairThumb(val size: Int, val iv: ByteArray, val ciphertext: ByteArray)

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
