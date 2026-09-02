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

/** One `R#` item, trimmed to what the detail screen's rendition list and "view
 * original" action need — see [fr.enry.archivist.data.remote.RenditionDto]'s own doc
 * for why the wire DTO itself is already this narrow. */
data class RenditionSummary(
    val renditionId: String,
    val role: String,
    val ext: String,
    val mime: String,
    val s3Key: String,
    val bytes: Long,
    val plainBytes: Long,
    val width: Int,
    val height: Int,
    val encIv: String?,
    val encChunkSize: Long,
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
    val encDek: String,
    val takenAt: String,
    val tzOffsetMin: Int,
    val takenAtSrc: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val primaryRend: String?,
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
                encDek = meta.encDek,
                takenAt = meta.takenAt,
                tzOffsetMin = meta.tzOffsetMin,
                takenAtSrc = meta.takenAtSrc,
                mime = meta.mime,
                width = meta.width,
                height = meta.height,
                primaryRend = meta.primaryRend,
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

        /** Fetches and decrypts one rendition's full ciphertext — the "original on
         * demand only" of plan step 2.12: never called from [fetchDetail] itself, only
         * from an explicit user action, since it's a paid retrieval against a tiered S3
         * object (`design.md`). Reads the whole plaintext into memory rather than
         * streaming to a file: this app's originals are individual photos/short videos,
         * not the kind of size where that matters, and every other decrypt path in this
         * codebase (thumbnails, EXIF) already does the same. */
        suspend fun downloadOriginal(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
        ): ByteArray {
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
    }

/** Wraps a successfully-decrypted [ExifBlob] purely so [decryptExif] can return "ran,
 * produced nothing" ([ExifBlob]-shaped but all-null never actually happens — see
 * [ExifBlob.from]) distinctly from "didn't run" via nullability of the wrapper itself. */
private data class DecryptedExif(val blob: ExifBlob)

private fun RenditionDto.toSummary() =
    RenditionSummary(
        renditionId = renditionId,
        role = role,
        ext = ext,
        mime = mime,
        s3Key = s3Key,
        bytes = bytes,
        plainBytes = plainBytes,
        width = width,
        height = height,
        encIv = encIv,
        encChunkSize = encChunkSize,
    )

private fun photoUrl(
    apiBase: String,
    photoId: String,
) = "$apiBase/photos/$photoId"

private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)

private val exifJson = Json { ignoreUnknownKeys = true }
