package fr.enry.archivist.data.repo

import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.ImageLockedException
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.StreamingCipher
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PhotoDetailResponse
import fr.enry.archivist.data.remote.RenditionDto
import fr.enry.archivist.domain.ExifBlob
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One `R#` item, trimmed to what the detail screen's rendition list, "view original"
 * action, and "Details" dialog need — see [fr.enry.archivist.data.remote.RenditionDto]'s
 * own doc for why the wire DTO itself is already this narrow. */
data class RenditionSummary(
    val renditionId: String,
    val role: String,
    val path: String,
    val ext: String,
    val mime: String,
    val s3Key: String,
    val contentHash: String,
    val bytes: Long,
    val plainBytes: Long,
    val width: Int,
    val height: Int,
    val encIv: String?,
    val encChunkSize: Long,
    val addedAt: String,
)

/**
 * Plan step 2.12: everything the photo-detail screen shows beyond what
 * [fr.enry.archivist.data.local.db.PhotoEntity] (the timeline's own Room cache) already
 * has — camera identity (from decrypted EXIF), [takenAtSrc] (for the approximate-date
 * marker), and the rendition list. [cameraMake]/[cameraModel] are both null exactly
 * when the asset had no EXIF worth encrypting ([exifDecryptFailed] distinguishes that
 * from "EXIF existed but this device couldn't decrypt it," e.g. a stale/locked master
 * key — the UI shouldn't silently show "no camera" for the latter).
 */
data class PhotoDetail(
    val photoId: String,
    val stem: String,
    val encDek: String,
    val takenAt: String,
    val tzOffsetMin: Int,
    val takenAtSrc: String,
    val tzSrc: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val primaryRend: String?,
    val renditionsCount: Int,
    val groupSrc: String,
    val deviceKey: String?,
    val status: String,
    val uploadedAt: String,
    val deletedAt: String?,
    val deletedBy: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val exifDecryptFailed: Boolean,
    val renditions: List<RenditionSummary>,
)

/**
 * Plan step 2.12. Fetches `GET /photos/{photoId}` and decrypts its `exifEnc` blob
 * on-device with the in-memory master key — the server can't do this itself (it never
 * holds the key), and it's the whole reason `exifEnc` exists rather than the server
 * indexing camera make/model itself. Also serves the "original on demand" action
 * ([downloadOriginal]): a plain unauthenticated GET against the `media` CloudFront
 * behavior (same model as [fr.enry.archivist.crypto.EncryptedImageFetcher]'s `thumbs`
 * fetch — see api.md; not written with the literal path here since a `/` immediately
 * followed by `*` opens a *nested* Kotlin block comment and corrupts this KDoc for
 * KSP's own symbol resolution, confirmed by bisection, even though the ordinary Kotlin
 * compiler tolerates it), decrypted with the asset's own DEK — renditions have no
 * `encDek` of their own (`src/core/items.ts`), they share the `#META` item's.
 */
@Singleton
class PhotoDetailRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val masterKeyHolder: MasterKeyHolder,
        private val okHttpClient: OkHttpClient,
    ) {
        suspend fun fetchDetail(photoId: String): PhotoDetail {
            val instance = instanceStore.current.first() ?: throw IOException("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val response = api.getPhoto(photoUrl(instance.document.apiBase, photoId))
            return response.toDetail()
        }

        private fun PhotoDetailResponse.toDetail(): PhotoDetail {
            val exif = decryptExif(meta.photoId, meta.encDek, meta.exifEnc, meta.exifIv)
            return PhotoDetail(
                photoId = meta.photoId,
                stem = meta.stem,
                encDek = meta.encDek,
                takenAt = meta.takenAt,
                tzOffsetMin = meta.tzOffsetMin,
                takenAtSrc = meta.takenAtSrc,
                tzSrc = meta.tzSrc,
                mime = meta.mime,
                width = meta.width,
                height = meta.height,
                primaryRend = meta.primaryRend,
                renditionsCount = meta.renditions,
                groupSrc = meta.groupSrc,
                deviceKey = meta.deviceKey,
                status = meta.status,
                uploadedAt = meta.uploadedAt,
                deletedAt = meta.deletedAt,
                deletedBy = meta.deletedBy,
                cameraMake = exif?.blob?.cameraMake,
                cameraModel = exif?.blob?.cameraModel,
                exifDecryptFailed = meta.exifEnc != null && exif == null,
                renditions = renditions.map { it.toSummary() },
            )
        }

        /** Null when there's nothing to decrypt ([encExif] absent) or decryption
         * couldn't run (no master key / a tamper or key mismatch) — [exifDecryptFailed]
         * above tells those two cases apart for the UI. */
        private fun decryptExif(
            photoId: String,
            encDek: String,
            encExif: String?,
            exifIv: String?,
        ): DecryptedExif? {
            if (encExif == null || exifIv == null) return null
            val masterKey = masterKeyHolder.current.value ?: return null
            return runCatching {
                val dek = masterKey.unwrapDek(decode(encDek))
                val plaintext =
                    WholeObjectCipher.decrypt(dek, decode(exifIv), Aad.of(photoId, ObjectRef.Exif), decode(encExif))
                exifJson.decodeFromString(ExifBlob.serializer(), plaintext.toString(Charsets.UTF_8))
            }.getOrNull()?.let(::DecryptedExif)
        }

        /** Fetches and decrypts one rendition **into memory**. Only for things that are small
         * enough to hold whole: a still image, say. For anything that might be a video use
         * [downloadOriginalToFile], which never holds more than one crypto segment.
         *
         * This used to be the only path, on the stated assumption that originals are "individual
         * photos/short videos, not the kind of size where that matters". That was wrong: it read
         * the whole ciphertext into a `ByteArrayOutputStream` (whose buffer doubles), so a
         * phone video past ~100 MB needed a single 128 MiB allocation and killed the app with
         * an `OutOfMemoryError` on Android's 256 MB heap -- reported as "repairing thumbnails of
         * some videos crashes the app". It now refuses an oversized rendition with an ordinary
         * error instead of crashing. */
        suspend fun downloadOriginal(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
        ): ByteArray {
            if (rendition.plainBytes > MAX_IN_MEMORY_ORIGINAL_BYTES) {
                throw IOException("this file is too large to open in memory (${rendition.plainBytes / 1_048_576} MB)")
            }
            val instance = instanceStore.current.first() ?: throw IOException("no connected instance")
            val masterKey = masterKeyHolder.current.value ?: throw ImageLockedException()
            val dek = masterKey.unwrapDek(decode(encDek))

            val url = "https://${instance.host}/media/${rendition.s3Key}"
            val ciphertext =
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
                        response.body?.bytes() ?: throw IOException("empty response body for $url")
                    }
                }

            val aad = Aad.of(photoId, ObjectRef.Rendition(rendition.renditionId))
            return if (rendition.encChunkSize == 0L) {
                val iv = rendition.encIv?.let(::decode) ?: throw IOException("whole-object rendition missing encIv")
                WholeObjectCipher.decrypt(dek, iv, aad, ciphertext)
            } else {
                StreamingCipher.decryptingStream(dek, aad, ByteArrayInputStream(ciphertext)).use { it.readBytes() }
            }
        }

        /**
         * Fetches one rendition and decrypts it **into [target]** without ever holding it whole:
         * the HTTP body is piped straight through [StreamingCipher.decryptingStream] into the
         * file, so memory use is one crypto segment (1 MiB) regardless of the video's size. This
         * is what repair (when it falls back to the server copy) and "View original" of a video
         * use. On any failure [target] is deleted, never left half-written.
         */
        suspend fun downloadOriginalToFile(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
            target: File,
        ) {
            val instance = instanceStore.current.first() ?: throw IOException("no connected instance")
            val masterKey = masterKeyHolder.current.value ?: throw ImageLockedException()
            val dek = masterKey.unwrapDek(decode(encDek))
            val url = "https://${instance.host}/media/${rendition.s3Key}"
            val aad = Aad.of(photoId, ObjectRef.Rendition(rendition.renditionId))

            withContext(Dispatchers.IO) {
                try {
                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("GET $url failed: HTTP ${response.code}")
                        val body = response.body ?: throw IOException("empty response body for $url")
                        decryptToFile(body.byteStream(), dek, aad, rendition.encChunkSize, rendition.encIv, target)
                    }
                } catch (e: Throwable) {
                    target.delete()
                    throw e
                }
            }
        }
    }

/** Largest rendition [PhotoDetailRepository.downloadOriginal] will hold in memory: the
 * ciphertext, the plaintext, and (for an image) the decoded bitmap all coexist, on a heap of
 * ~256 MB. */
internal const val MAX_IN_MEMORY_ORIGINAL_BYTES = 48L * 1024 * 1024

/** crypto-format.md's whole-object mode is only ever used below the 32 MiB streaming threshold;
 * anything claiming to be whole-object and bigger than this (plus its tag) is refused rather
 * than buffered. */
private const val MAX_WHOLE_OBJECT_BYTES = 33_554_432L + 16

/**
 * The decrypt-to-file half of [PhotoDetailRepository.downloadOriginalToFile], separated so it is
 * testable without a network: streaming mode (`encChunkSize > 0`) copies through the decrypting
 * stream in 64 KiB pieces; whole-object mode (necessarily small) is read bounded and decrypted
 * in one go.
 */
internal fun decryptToFile(
    source: InputStream,
    dek: ByteArray,
    aad: ByteArray,
    encChunkSize: Long,
    encIv: String?,
    target: File,
) {
    if (encChunkSize == 0L) {
        val iv = encIv?.let(::decode) ?: throw IOException("whole-object rendition missing encIv")
        val ciphertext = source.readNBytes((MAX_WHOLE_OBJECT_BYTES + 1).toInt())
        if (ciphertext.size > MAX_WHOLE_OBJECT_BYTES) throw IOException("whole-object rendition is larger than the format allows")
        target.writeBytes(WholeObjectCipher.decrypt(dek, iv, aad, ciphertext))
    } else {
        StreamingCipher.decryptingStream(dek, aad, source).use { plain ->
            target.outputStream().use { out -> plain.copyTo(out, 64 * 1024) }
        }
    }
}

/** Wraps a successfully-decrypted [ExifBlob] purely so [decryptExif] can return "ran,
 * produced nothing" ([ExifBlob]-shaped but all-null never actually happens — see
 * [ExifBlob.from]) distinctly from "didn't run" via nullability of the wrapper itself. */
private data class DecryptedExif(val blob: ExifBlob)

private fun RenditionDto.toSummary() =
    RenditionSummary(
        renditionId = renditionId,
        role = role,
        path = path,
        ext = ext,
        mime = mime,
        s3Key = s3Key,
        contentHash = contentHash,
        bytes = bytes,
        plainBytes = plainBytes,
        width = width,
        height = height,
        encIv = encIv,
        encChunkSize = encChunkSize,
        addedAt = addedAt,
    )

private fun photoUrl(
    apiBase: String,
    photoId: String,
) = "$apiBase/photos/$photoId"

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

private val exifJson = Json { ignoreUnknownKeys = true }
