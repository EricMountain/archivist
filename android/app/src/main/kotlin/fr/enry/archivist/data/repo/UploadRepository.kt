package fr.enry.archivist.data.repo

import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.StreamingCipher
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.local.db.LocalTombstoneDao
import fr.enry.archivist.data.local.db.LocalTombstoneEntity
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.remote.ArchivistApi
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PostUploadRequest
import fr.enry.archivist.data.remote.PostUploadResponse
import fr.enry.archivist.data.remote.ThumbDescriptorDto
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.domain.ExifExtractor
import fr.enry.archivist.domain.Timestamps
import fr.enry.archivist.domain.Ulid
import fr.enry.archivist.sync.MediaStoreSource
import fr.enry.archivist.sync.Thumbnailer
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** What [UploadRepository.uploadOne] did, mapped onto WorkManager's `Result` by
 * [fr.enry.archivist.sync.UploadWorker] — kept as its own type rather than `Result<T>`/
 * `WorkManager.Result` directly so the repository stays testable without a WorkManager
 * dependency. */
sealed interface UploadOutcome {
    data object Success : UploadOutcome

    /** Network error, a `5xx`, a locked master key, or anything else that might
     * resolve itself — retry with backoff, per plan step 2.10's "distinguish permanent
     * failures... from transient ones". */
    data object Retry : UploadOutcome

    data class PermanentFailure(val message: String) : UploadOutcome
}

/** 32 MiB — crypto-format.md's own default threshold, "pure client policy" recorded
 * per-object via `encChunkSize`, so retuning this later never invalidates anything
 * already uploaded. */
private const val STREAMING_THRESHOLD_BYTES = 33_554_432L
private const val STREAMING_CHUNK_SIZE = 1_048_576L

/**
 * Plan step 2.10: "extract metadata → thumbnails → `POST /uploads` → stream-encrypt and
 * PUT → mark done" for exactly one [UploadQueueEntity] row — [fr.enry.archivist.sync.UploadWorker]
 * is the WorkManager wrapper that calls [uploadOne] once per file.
 *
 * **Why the crypto is generated *before* the metadata POST, not after, despite the
 * plan text's ordering.** `crypto-format.md`'s AAD embeds `photoId`, and thumbnails/
 * EXIF have to be encrypted (to fill the POST body's `exifEnc`/`thumbs[*].iv`) before
 * the call that would otherwise mint one — so this class always generates its own
 * candidate `photoId`/DEK first. `created`/`resumed` on the response then say whether
 * that candidate survived:
 * - `created`: a brand-new asset, using this device's candidate exactly as generated —
 *   everything already encrypted is valid, PUT it all.
 * - `resumed`: this device's own asset, already committed by an earlier attempt that
 *   died before its bytes arrived (a process kill, lost connectivity — exactly what
 *   this step's "Done when" tests). The candidate DEK is discarded for the *real* one
 *   (`encDek`/`encKeyId`), and the rendition's `encIv`/`encChunkSize` are reused
 *   bit-for-bit from the response rather than regenerated, since they were already
 *   fixed by whichever attempt actually committed. Thumbnails are re-encrypted under
 *   the real DEK and re-uploaded — safe, because the server re-records `#META.thumbs`
 *   to match on every `resumed` response.
 * - neither: attached to a *different*, already-existing asset (e.g. a RAW+JPEG
 *   stem match). The candidate is entirely wrong (bound to a photoId/DEK this asset
 *   never used) and discarded; only the original rendition is re-encrypted under the
 *   real DEK and PUT. Thumbnails are deliberately **not** re-uploaded here — the
 *   server doesn't record `#META.thumbs` on a plain attach even when this rendition
 *   becomes primary (a known, separate gap; see this step's STATUS.md note), so
 *   PUTting them would silently overwrite a *different*, still-correctly-referenced
 *   thumbnail object with ciphertext under a mismatched IV.
 *
 * See "Resuming an interrupted upload" and "Why the client gets to propose a photoId"
 * in design.md for the server-side half of this.
 */
@Singleton
class UploadRepository
    @Inject
    constructor(
        private val uploadQueueDao: UploadQueueDao,
        private val localTombstoneDao: LocalTombstoneDao,
        private val mediaStoreSource: MediaStoreSource,
        private val thumbnailer: Thumbnailer,
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val enrolmentStore: EnrolmentStore,
        private val masterKeyHolder: MasterKeyHolder,
        private val deviceRepository: DeviceRepository,
        private val baseOkHttpClient: OkHttpClient,
    ) {
        /** The current master key version (`mk-<n>`) rarely changes (only on
         * rotation, which nothing in this app triggers yet) — cached for the life of
         * the process rather than fetched via `GET /keys` before every single file. */
        @Volatile
        private var cachedMasterKeyVer: String? = null

        /** Streaming a large file's ciphertext can run well past the base client's
         * short timeouts (`NetworkModule`'s 10s connect/read is sized for small JSON
         * calls) — no fixed cap here instead, since a slow-but-alive connection
         * shouldn't be treated as failed just because a video takes a while. */
        private val putClient: OkHttpClient by lazy {
            baseOkHttpClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        }

        suspend fun uploadOne(queueId: Long): UploadOutcome {
            var row = uploadQueueDao.getById(queueId) ?: return UploadOutcome.Success
            if (row.state == UploadState.DONE) return UploadOutcome.Success

            val contentHash = row.contentHash ?: return failPermanently(row, "no content hash recorded")
            val plainBytes = row.plainBytes ?: return failPermanently(row, "no file size recorded")

            val masterKey = masterKeyHolder.current.value ?: return UploadOutcome.Retry
            val instance = instanceStore.current.first() ?: return UploadOutcome.Retry
            val api = apiFor(instance)
            val apiBase = instance.document.apiBase

            return try {
                row = persist(row.copy(state = UploadState.EXTRACTING))

                val exif =
                    withContext(Dispatchers.IO) {
                        mediaStoreSource.openInputStream(row.localUri).use { ExifExtractor.extract(it) }
                    }
                val fileMtime = row.fileMtimeEpochSec?.let(Instant::ofEpochSecond) ?: Instant.now()
                val deviceKey =
                    ExifExtractor.deviceKey(exif.cameraMake, exif.cameraModel, exif.cameraSerial).takeUnless { it == "-|-|-" }
                // Plan step 2.14: the real per-camera default from Settings > Devices
                // (local cache, no network round trip here) — see DeviceRepository's
                // own doc for what this used before that setting existed (this
                // device's own current system timezone, which design.md's ladder
                // never actually specifies as a rung and which isn't "the device" the
                // ladder means at all).
                val deviceOffsetMin = deviceKey?.let { deviceRepository.tzOffsetMinFor(it) }
                val resolved =
                    Timestamps.resolve(exif = exif, fileMtime = fileMtime, deviceDefaultOffsetMin = deviceOffsetMin)
                val (takenAt, takenAtSrc, tzOffsetMin, tzSrc) =
                    if (resolved != null) {
                        TimestampFields(resolved.takenAt.toString(), resolved.takenAtSrc.wireValue, resolved.tzOffsetMin, resolved.tzSrc.wireValue)
                    } else {
                        // Neither client-side rung produced anything plausible.
                        // design.md's ladder has server-side rungs (s3-mtime/upload)
                        // for exactly this, but uploads.ts doesn't yet accept an
                        // omitted takenAt to fall through to them — send "upload"
                        // (now) directly rather than blocking on a backend change
                        // this step doesn't otherwise need.
                        TimestampFields(nowIso(), "upload", 0, "assumed-utc")
                    }
                val mime = ExifExtractor.mimeFromDisplayName(row.displayName) ?: "application/octet-stream"

                row =
                    persist(
                        row.copy(
                            state = UploadState.THUMBNAILING,
                            mime = mime,
                            width = exif.widthPx,
                            height = exif.heightPx,
                            takenAt = takenAt,
                            tzOffsetMin = tzOffsetMin,
                            takenAtSrc = takenAtSrc,
                            tzSrc = tzSrc,
                        ),
                    )

                val thumbnails = thumbnailer.generate(row.localUri)
                // A source no wider/taller than the largest rung comes back at its own
                // real size (Thumbnailer never upscales) — a reasonable stand-in for
                // the original's own dimensions when EXIF had none. When the source
                // *exceeds* the largest rung and EXIF also had nothing, this
                // under-reports — a known, narrow gap, not silently assumed correct;
                // see this step's STATUS.md note.
                val largest = thumbnails.maxByOrNull { it.longestEdge }
                val width = exif.widthPx ?: largest?.width ?: 1
                val height = exif.heightPx ?: largest?.height ?: 1

                row = persist(row.copy(state = UploadState.UPLOADING, width = width, height = height))

                val masterKeyVer = currentMasterKeyVer(api, apiBase, instance) ?: return UploadOutcome.Retry

                val candidatePhotoId = Ulid.generate()
                val candidateDek = EnvelopeCrypto.generateDek()
                val chunkSize = if (plainBytes > STREAMING_THRESHOLD_BYTES) STREAMING_CHUNK_SIZE else 0L
                val candidateIv = if (chunkSize == 0L) EnvelopeCrypto.generateIv() else null

                val exifBlob = ExifBlob.from(exif)
                val exifIv = exifBlob?.let { EnvelopeCrypto.generateIv() }
                val exifCiphertext =
                    exifBlob?.let {
                        WholeObjectCipher.encrypt(
                            candidateDek,
                            exifIv!!,
                            Aad.of(candidatePhotoId, ObjectRef.Exif),
                            exifJson.encodeToString(ExifBlob.serializer(), it).toByteArray(Charsets.UTF_8),
                        )
                    }

                val encryptedThumbs =
                    thumbnails.map { t ->
                        val iv = EnvelopeCrypto.generateIv()
                        val ciphertext =
                            WholeObjectCipher.encrypt(
                                candidateDek,
                                iv,
                                Aad.of(candidatePhotoId, ObjectRef.Thumbnail(t.longestEdge)),
                                t.bytes,
                            )
                        EncryptedThumb(t.longestEdge, plaintext = t.bytes, iv = iv, ciphertext = ciphertext)
                    }

                val request =
                    PostUploadRequest(
                        path = "${row.folderUri}/${row.displayName}",
                        plainBytes = plainBytes,
                        bytes = ciphertextLength(plainBytes, chunkSize),
                        mime = mime,
                        width = width,
                        height = height,
                        contentHash = contentHash,
                        takenAt = takenAt,
                        takenAtSrc = takenAtSrc,
                        tzOffsetMin = tzOffsetMin,
                        tzSrc = tzSrc,
                        deviceKey = deviceKey,
                        exifEnc = exifCiphertext?.let(::encode),
                        exifIv = exifIv?.let(::encode),
                        encDek = encode(masterKey.wrapDek(candidateDek)),
                        encKeyId = masterKeyVer,
                        encIv = candidateIv?.let(::encode),
                        encChunkSize = chunkSize,
                        thumbs =
                            encryptedThumbs.associate {
                                it.size.toString() to ThumbDescriptorDto(it.ciphertext.size.toLong(), encode(it.iv))
                            }.ifEmpty { null },
                        photoId = candidatePhotoId,
                    )

                val httpResponse = api.postUpload(uploadsUrl(apiBase), request)
                if (!httpResponse.isSuccessful) return classifyHttpFailure(row, httpResponse.code())
                val response = httpResponse.body() ?: return recordAttempt(row, "empty response body")

                handleResponse(
                    row = row,
                    response = response,
                    contentHash = contentHash,
                    mime = mime,
                    plainBytes = plainBytes,
                    chunkSize = chunkSize,
                    candidateDek = candidateDek,
                    candidateIv = candidateIv,
                    encryptedThumbs = encryptedThumbs,
                    masterKey = masterKey,
                )
            } catch (e: IOException) {
                recordAttempt(row, e.message ?: "network error")
            } catch (e: Exception) {
                recordAttempt(row, e.message ?: (e::class.simpleName ?: "unknown error"))
            }
        }

        private suspend fun handleResponse(
            row: UploadQueueEntity,
            response: PostUploadResponse,
            contentHash: String,
            mime: String,
            plainBytes: Long,
            chunkSize: Long,
            candidateDek: ByteArray,
            candidateIv: ByteArray?,
            encryptedThumbs: List<EncryptedThumb>,
            masterKey: MasterKey,
        ): UploadOutcome {
            if (response.skipped == true) {
                // kind: purged, no reAddDeleted -- design.md's "Purge tombstones":
                // deliberately quiet on the wire, but never retried again locally.
                localTombstoneDao.upsert(LocalTombstoneEntity(contentHash, nowIso()))
                markDone(row, photoId = null, renditionId = null)
                return UploadOutcome.Success
            }

            val photoId = response.photoId ?: return recordAttempt(row, "response missing photoId")
            val renditionId = response.renditionId

            val original = response.originalUpload
            if (original == null || renditionId == null) {
                // duplicate / restored (trashed-and-recorded, or a bare live dup):
                // nothing left for this device to upload.
                markDone(row, photoId, renditionId)
                return UploadOutcome.Success
            }

            val created = response.created == true
            val resumed = response.resumed == true

            val dek: ByteArray
            val effectiveChunkSize: Long
            val iv: ByteArray?
            val uploadThumbs: Boolean

            if (created) {
                dek = candidateDek
                effectiveChunkSize = chunkSize
                iv = candidateIv
                uploadThumbs = true
            } else {
                val encDekB64 = response.encDek ?: return recordAttempt(row, "resume/attach response missing encDek")
                dek = masterKey.unwrapDek(decode(encDekB64))
                if (resumed) {
                    effectiveChunkSize = response.encChunkSize ?: return recordAttempt(row, "resumed response missing encChunkSize")
                    iv =
                        if (effectiveChunkSize == 0L) {
                            response.encIv?.let(::decode) ?: return recordAttempt(row, "resumed response missing encIv")
                        } else {
                            null
                        }
                    uploadThumbs = true
                } else {
                    // Attached to a different, already-existing asset. The DEK
                    // changes (the asset's real one, not the candidate) but the IV
                    // doesn't: this rendition item is brand new either way, and
                    // buildRendition (uploads.ts) records encIv/encChunkSize straight
                    // off *this same request's body* regardless of create-vs-attach --
                    // so whatever candidateIv/chunkSize this call already sent is
                    // exactly what the server just committed, and must be reused, not
                    // regenerated.
                    effectiveChunkSize = chunkSize
                    iv = candidateIv
                    uploadThumbs = false
                }
            }

            putOriginal(
                url = original.url,
                mime = mime,
                plainBytes = plainBytes,
                chunkSize = effectiveChunkSize,
                dek = dek,
                iv = iv,
                photoId = photoId,
                renditionId = renditionId,
                localUri = row.localUri,
            )

            if (uploadThumbs) {
                val thumbUploads = response.thumbUploads ?: emptyMap()
                for (t in encryptedThumbs) {
                    val url = thumbUploads[t.size.toString()] ?: continue
                    // created: the candidate DEK already encrypted these correctly.
                    // resumed: the candidate DEK was wrong (a different real DEK just
                    // came back) -- re-encrypt the same plaintext under it, reusing
                    // the *same* t.iv rather than a fresh one. That's safe (GCM only
                    // needs IV uniqueness per key, and this is a different key) and
                    // necessary: t.iv is exactly what this same request's `thumbs`
                    // descriptor already told the server, and `resumeUpload` just
                    // re-recorded #META.thumbs[size].iv to match it -- reusing it here
                    // keeps what's about to land in S3 consistent with what the
                    // metadata now claims decrypts it.
                    val bytes =
                        if (created) {
                            t.ciphertext
                        } else {
                            WholeObjectCipher.encrypt(dek, t.iv, Aad.of(photoId, ObjectRef.Thumbnail(t.size)), t.plaintext)
                        }
                    putBytes(url, "image/webp", bytes)
                }
            }

            markDone(row, photoId, renditionId)
            return UploadOutcome.Success
        }

        private suspend fun putOriginal(
            url: String,
            mime: String,
            plainBytes: Long,
            chunkSize: Long,
            dek: ByteArray,
            iv: ByteArray?,
            photoId: String,
            renditionId: String,
            localUri: String,
        ) {
            val aad = Aad.of(photoId, ObjectRef.Rendition(renditionId))
            val length = ciphertextLength(plainBytes, chunkSize)
            val body =
                object : RequestBody() {
                    override fun contentType() = mime.toMediaTypeOrNull()

                    override fun contentLength() = length

                    override fun writeTo(sink: BufferedSink) {
                        val cipherOut =
                            if (chunkSize == 0L) {
                                WholeObjectCipher.encryptingStream(dek, requireNotNull(iv), aad, sink.outputStream())
                            } else {
                                StreamingCipher.encryptingStream(dek, aad, sink.outputStream())
                            }
                        mediaStoreSource.openInputStream(localUri).use { input -> cipherOut.use { out -> input.copyTo(out) } }
                    }
                }
            put(url, body)
        }

        private suspend fun putBytes(
            url: String,
            mime: String,
            bytes: ByteArray,
        ) {
            put(url, bytes.toRequestBody(mime.toMediaTypeOrNull()))
        }

        private suspend fun put(
            url: String,
            body: RequestBody,
        ) {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).put(body).build()
                putClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("PUT to $url failed: HTTP ${resp.code}")
                }
            }
        }

        private suspend fun currentMasterKeyVer(
            api: ArchivistApi,
            apiBase: String,
            instance: StoredInstance,
        ): String? {
            cachedMasterKeyVer?.let { return it }
            val wrapId = enrolmentStore.deviceWrapId(instance.host) ?: return null
            val wrap = api.getKeys(keysUrl(apiBase), wrapId = wrapId).wraps.find { it.wrapId == wrapId } ?: return null
            return wrap.masterKeyVer.also { cachedMasterKeyVer = it }
        }

        private fun apiFor(instance: StoredInstance): ArchivistApi =
            archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)

        private suspend fun persist(entry: UploadQueueEntity): UploadQueueEntity {
            val updated = entry.copy(updatedAt = nowIso())
            uploadQueueDao.update(updated)
            return updated
        }

        private suspend fun markDone(
            row: UploadQueueEntity,
            photoId: String?,
            renditionId: String?,
        ) {
            persist(row.copy(state = UploadState.DONE, photoId = photoId, renditionId = renditionId, lastError = null))
        }

        private suspend fun recordAttempt(
            row: UploadQueueEntity,
            error: String,
        ): UploadOutcome {
            persist(row.copy(attempts = row.attempts + 1, lastError = error))
            return UploadOutcome.Retry
        }

        private suspend fun failPermanently(
            row: UploadQueueEntity,
            error: String,
        ): UploadOutcome {
            persist(row.copy(state = UploadState.FAILED, attempts = row.attempts + 1, lastError = error))
            return UploadOutcome.PermanentFailure(error)
        }

        private suspend fun classifyHttpFailure(
            row: UploadQueueEntity,
            code: Int,
        ): UploadOutcome =
            if (code in 400..499) {
                failPermanently(row, "server rejected upload (HTTP $code)")
            } else {
                recordAttempt(row, "server error (HTTP $code)")
            }
    }

private data class TimestampFields(val takenAt: String, val takenAtSrc: String, val tzOffsetMin: Int, val tzSrc: String)

private class EncryptedThumb(val size: Int, val plaintext: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

private fun ciphertextLength(
    plainBytes: Long,
    chunkSize: Long,
): Long =
    if (chunkSize == 0L) {
        plainBytes + WholeObjectCipher.TAG_LEN_BYTES
    } else {
        StreamingCipher.ciphertextLength(plainBytes)
    }

private fun uploadsUrl(apiBase: String) = "$apiBase/uploads"

private fun keysUrl(apiBase: String) = "$apiBase/keys"

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

private val exifJson = Json { ignoreUnknownKeys = true }
