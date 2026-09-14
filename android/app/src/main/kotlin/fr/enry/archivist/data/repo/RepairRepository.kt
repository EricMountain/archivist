package fr.enry.archivist.data.repo

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
import fr.enry.archivist.sync.Thumbnailer
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

    data class Error(val message: String) : RepairOutcome
}

/**
 * The "repair a photo" menu action: regenerates and re-uploads an asset's thumbnail
 * ladder when it came out blank — a device that died mid-thumbnail, an
 * [android.graphics.ImageDecoder] hiccup on one particular file, or similar. Deliberately
 * narrow, per design.md's "Changing the ladder later": thumbnails are client-generated,
 * so fixing one requires a client that still holds the original plaintext, not a
 * server-side reprocess (the server never holds pixels at all).
 *
 * That plaintext is [UploadQueueDao.getByPhotoId] — the same table [DeleteRepository]
 * already uses to map a `photoId` back to the local file(s) this device uploaded it
 * from. **Repair only works from the device that originally uploaded the photo, and
 * only while that local file still exists** (not deleted via "Remove from both", not
 * cleared by the user outside this app) — there is no fallback to re-downloading and
 * decrypting the original from S3 here; see this class's own STATUS.md note for why
 * that was scoped out rather than half-built.
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
        private val okHttpClient: OkHttpClient,
    ) {
        /** [primaryRenditionId] picks which of this asset's local files to regenerate
         * from when it has more than one (a RAW+JPEG pair) — the same file the grid/
         * detail thumbnail is actually derived from server-side. Falls back to
         * whichever uploaded local file this device has for the photo when the detail
         * fetch hasn't resolved yet ([primaryRenditionId] null), which is right in the
         * overwhelmingly common single-rendition case and only a guess for a
         * not-yet-primary rendition on a multi-rendition asset. */
        suspend fun repairThumbnails(
            photoId: String,
            primaryRenditionId: String?,
            encDek: String,
        ): RepairOutcome {
            val masterKey = masterKeyHolder.current.value ?: return RepairOutcome.Error("locked — unlock to repair")
            val candidates = uploadQueueDao.getByPhotoId(photoId).filter { it.state == UploadState.DONE && it.mime != null }
            val row =
                candidates.find { it.renditionId == primaryRenditionId } ?: candidates.firstOrNull()
                    ?: return RepairOutcome.Error("original file not found on this device")

            val instance = instanceStore.current.first() ?: return RepairOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val apiBase = instance.document.apiBase

            return try {
                val dek = masterKey.unwrapDek(decode(encDek))
                val thumbnails = thumbnailer.generate(row.localUri, row.mime!!)
                val encrypted =
                    thumbnails.map { t ->
                        val iv = EnvelopeCrypto.generateIv()
                        val ciphertext =
                            WholeObjectCipher.encrypt(dek, iv, Aad.of(photoId, ObjectRef.Thumbnail(t.longestEdge)), t.bytes)
                        EncryptedRepairThumb(t.longestEdge, iv, ciphertext)
                    }
                val descriptors =
                    encrypted.associate { t -> t.size.toString() to ThumbDescriptorDto(t.ciphertext.size.toLong(), encode(t.iv)) }

                val httpResponse = api.postPhotoThumbs(thumbsUrl(apiBase, photoId), PostPhotoThumbsRequest(descriptors))
                if (!httpResponse.isSuccessful) return RepairOutcome.Error("server rejected repair (HTTP ${httpResponse.code()})")
                val body = httpResponse.body() ?: return RepairOutcome.Error("empty response body")

                for (t in encrypted) {
                    val url = body.thumbUploads[t.size.toString()] ?: continue
                    putBytes(url, t.ciphertext)
                }

                val refreshed = api.getPhotoAsTimelineEntry(photoUrl(apiBase, photoId))
                photoDao.upsertAll(listOf(refreshed.meta.toEntity()))
                RepairOutcome.Done
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
