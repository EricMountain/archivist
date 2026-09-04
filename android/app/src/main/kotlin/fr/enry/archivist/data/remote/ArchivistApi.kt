package fr.enry.archivist.data.remote

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.Response
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * The instance's own metadata API — `apiBase` from the discovery document, JWT
 * bearer-authenticated via [ArchivistAuthInterceptor]/[ArchivistAuthenticator]. Plan
 * step 2.5 adds the `/keys*` routes; later plan steps (2.6+) add the rest of
 * `docs/design/api.md`'s route table here.
 */
interface ArchivistApi {
    @POST
    suspend fun postSessionBootstrap(
        @Url url: String,
        @Body body: SessionBootstrapRequest,
    ): SessionBootstrapResponse

    /** [wrapId] requests full unwrapping material for *that one* wrapping — every
     * other entry in the response still comes back metadata-only (see `GET /keys` in
     * api.md). The server doesn't separately check that [wrapId] "belongs" to the
     * caller in any stronger sense than that: ownership is already scoped by the JWT,
     * so any wrap in the list is by definition this owner's own. */
    @GET
    suspend fun getKeys(
        @Url url: String,
        @Query("wrapId") wrapId: String? = null,
    ): KeysResponse

    @POST
    suspend fun postKey(
        @Url url: String,
        @Body body: PostKeyWrapRequest,
    ): PostKeyWrapResponse

    /** 204 No Content on success — declared as `Response<ResponseBody>` rather than a
     * bare `ResponseBody` (nullable or not) so the kotlinx-serialization converter is
     * never asked to parse an empty body, *and* so a non-2xx doesn't need Retrofit's
     * "throw `HttpException` automatically" behavior, which a raw-body suspend return
     * type would otherwise apply — `Response<T>` opts out of that, in exchange for the
     * caller checking `isSuccessful` itself. Confirmed by running this against a real
     * 204 in `EnrolmentRepositoryTest`: a non-nullable raw-body return type throws a
     * `NullPointerException` here (OkHttp/Retrofit represent a 204's body as `null`
     * regardless of what the declared type says), and even a *nullable* raw-body
     * return type didn't reliably suppress that in this Kotlin/Retrofit combination. */
    @DELETE
    suspend fun deleteKey(
        @Url url: String,
    ): Response<ResponseBody>

    @POST
    suspend fun postKeyVersion(
        @Url url: String,
    ): MasterKeyVersionResponse

    @PUT
    suspend fun putHashSecret(
        @Url url: String,
        @Body body: PutHashSecretRequest,
    ): Response<ResponseBody>

    /** `Response<T>` rather than a bare `HashSecretResponse` for the same reason as
     * [deleteKey]: a `404` (no device has ever called [putHashSecret] for this owner
     * yet) is an ordinary, expected outcome the caller checks for via `isSuccessful`,
     * not something that should throw `HttpException` on its own. */
    @GET
    suspend fun getHashSecret(
        @Url url: String,
    ): Response<HashSecretResponse>

    /** Plan step 2.10: the stem/hash handshake — see `POST /uploads` in `api.md` and
     * "Ingest"/"Resuming an interrupted upload" in `design.md`. Never throws on a
     * `4xx`/`5xx` on its own ([Response] rather than a bare return type), because
     * [fr.enry.archivist.data.repo.UploadRepository] has to tell a permanent failure
     * (bad metadata — stop retrying) apart from a transient one (retry with backoff),
     * which `HttpException` alone doesn't distinguish as cleanly as a status code does.
     */
    @POST
    suspend fun postUpload(
        @Url url: String,
        @Body body: PostUploadRequest,
    ): Response<PostUploadResponse>

    /** Plan step 2.11's `RemoteMediator` calls this once per page — see
     * [fr.enry.archivist.data.repo.TimelineRemoteMediator]. [cursor] is the opaque
     * string `dto.ts`'s `GET /photos` returns, never constructed client-side. */
    @GET
    suspend fun getPhotos(
        @Url url: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): PhotosPageResponse

    /** Plan step 2.12: single-asset detail — `GET /photos/{photoId}` in `api.md`. Unlike
     * [getPhotos], `routes/photos.ts`'s `getPhoto` returns the raw `#META`/`R#` items
     * (not `dto.ts`'s DTOs), so [PhotoDetailResponse]/[PhotoMetaDto]/[RenditionDto] only
     * declare the subset of those items' fields this screen needs — the converter's
     * `ignoreUnknownKeys` (see `NetworkModule`) drops the rest (`pk`/`sk`/`ownerId`/
     * `s3Bucket`/etc.) rather than requiring them. */
    @GET
    suspend fun getPhoto(
        @Url url: String,
    ): PhotoDetailResponse

    /** Plan step 2.13: `DELETE /photos/{photoId}` in `api.md` — trashes the whole asset.
     * `routes/photos.ts`'s `deletePhoto` accepts an optional `{deletedBy}` body and
     * defaults it server-side when absent, so this never sends one. `Response<T>` for
     * the same reason as [deleteKey]: a 204 with no body shouldn't go through Retrofit's
     * automatic-`HttpException`-on-non-2xx path, since the caller (`DeleteRepository`)
     * needs to distinguish a 404 (already trashed/deleted elsewhere) from other
     * failures. */
    @DELETE
    suspend fun deletePhoto(
        @Url url: String,
    ): Response<ResponseBody>

    /** Plan step 2.13: `GET /trash` in `api.md` — trashed assets pending purge, each
     * optionally carrying its primary rendition's blocked-re-upload counters (see
     * [TrashEntryDto]). */
    @GET
    suspend fun getTrash(
        @Url url: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): TrashPageResponse

    /** Plan step 2.14: `GET /devices` in `api.md` — every camera `deviceKey` this
     * owner's library has ever seen, for the Devices settings section. */
    @GET
    suspend fun getDevices(
        @Url url: String,
    ): DevicesResponse

    /** [PatchDeviceRequest] has no optional/defaulted fields on purpose — see its own
     * doc for why this always sends both, rather than relying on kotlinx.serialization
     * to distinguish "omitted" from "explicitly null" the way [PostUploadRequest]'s
     * comment already flags as unreliable with `encodeDefaults = false`. `Response<T>`
     * for the same reason as [deleteKey]: a 404 (this device was never seen by ingest)
     * is an ordinary outcome the caller checks for, not one that should throw. */
    @PATCH
    suspend fun patchDevice(
        @Url url: String,
        @Body body: PatchDeviceRequest,
    ): Response<ResponseBody>

    @DELETE
    suspend fun deleteDevice(
        @Url url: String,
    ): Response<ResponseBody>

    /** Plan step 2.14: `DELETE /account` in `api.md` — requires the caller to echo
     * back its own ownerId as an explicit confirmation. Retrofit's `@DELETE` refuses
     * to build a request with `@Body` at all ("Non-body HTTP method cannot contain
     * @Body" — a `RequestFactory` build-time failure, only surfaced by actually
     * calling this method, not by anything that compiles) even though HTTP itself
     * and OkHttp both allow a DELETE body and `routes/account.ts` requires one —
     * `@HTTP(hasBody = true)` is Retrofit's own documented escape hatch for exactly
     * this combination. `Response<T>` so the caller can distinguish a validation
     * failure (mistyped confirmation) from success without relying on `HttpException`. */
    @HTTP(method = "DELETE", path = "", hasBody = true)
    suspend fun deleteAccount(
        @Url url: String,
        @Body body: DeleteAccountRequest,
    ): Response<ResponseBody>
}

@Serializable
data class SessionBootstrapRequest(
    val homeTz: String? = null,
    val displayName: String? = null,
)

@Serializable
data class SessionBootstrapResponse(
    val userId: String,
    val ownerId: String,
    val created: Boolean,
)

@Serializable
data class KdfParamsDto(
    val alg: String,
    val m: String,
    val t: Int,
    val p: Int,
)

/** One `W#` item. Metadata-only entries (every wrap that isn't the one the caller
 * asked for by `wrapId`) simply have the unwrapping-material fields null — see
 * `toMeta` server-side in `routes/keys.ts`. */
@Serializable
data class KeyWrapDto(
    val wrapId: String,
    val kind: String,
    val label: String,
    val masterKeyVer: String,
    val rotatedAt: String? = null,
    val wrapAlg: String? = null,
    val wrappedKey: String? = null,
    val epk: String? = null,
    val credentialId: String? = null,
    val prfSalt: String? = null,
    val kdfSalt: String? = null,
    val kdfParams: KdfParamsDto? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class KeysResponse(val wraps: List<KeyWrapDto>)

@Serializable
data class PostKeyWrapRequest(
    val kind: String,
    val label: String,
    val wrapAlg: String,
    val wrappedKey: String,
    val epk: String? = null,
    val credentialId: String? = null,
    val prfSalt: String? = null,
    val kdfSalt: String? = null,
    val kdfParams: KdfParamsDto? = null,
)

@Serializable
data class PostKeyWrapResponse(val wrapId: String, val masterKeyVer: String)

@Serializable
data class MasterKeyVersionResponse(val masterKeyVer: String, val rotatedAt: String)

@Serializable
data class PutHashSecretRequest(val encHashSecret: String, val hashSecretKeyId: String)

@Serializable
data class HashSecretResponse(val encHashSecret: String, val hashSecretKeyId: String)

/** The generic `{"error": "..."}` shape every non-`ApiError` response from the
 * Archivist API (not Cognito's) uses — see `src/lambda/api/index.ts`. */
@Serializable
data class ArchivistErrorBody(val error: String? = null)

@Serializable
data class ThumbDescriptorDto(val bytes: Long, val iv: String)

/** `src/lambda/api/routes/uploads.ts`'s `UploadBody`. [photoId] is this device's
 * candidate — see design.md's "Why the client gets to propose a photoId". [encIv] is
 * whole-object mode only (`encChunkSize == 0L`); left `null` for streaming mode, per
 * crypto-format.md, and kotlinx.serialization's default `encodeDefaults = false` then
 * drops it from the JSON entirely rather than sending an explicit `null` — matching
 * `uploads.ts`'s own "encIv is required for whole-object mode" check, not just
 * satisfying it by accident. */
@Serializable
data class PostUploadRequest(
    val path: String,
    val plainBytes: Long,
    val bytes: Long,
    val mime: String,
    val width: Int,
    val height: Int,
    val contentHash: String,
    val takenAt: String,
    val takenAtSrc: String,
    val tzOffsetMin: Int,
    val tzSrc: String,
    val deviceKey: String? = null,
    val exifEnc: String? = null,
    val exifIv: String? = null,
    val encDek: String,
    val encKeyId: String,
    val encIv: String? = null,
    val encChunkSize: Long,
    val thumbs: Map<String, ThumbDescriptorDto>? = null,
    val reAddDeleted: Boolean? = null,
    val groupWith: String? = null,
    val noGroup: Boolean? = null,
    val photoId: String? = null,
)

/** One `timelineEntryDto` entry (`dto.ts`), for `GET /photos`. Reuses
 * [fr.enry.archivist.data.local.db.ThumbEntry] directly rather than a parallel DTO
 * class — it's already `@Serializable` and its fields (`bucket`/`key`/`iv`/`bytes`)
 * are exactly the wire shape. `thumbs`' keys are the size ("256"/"1024"/"2048") as a
 * JSON object key, i.e. a string even though the server's own `ThumbMap` is keyed by
 * number — `TimelineRemoteMediator.kt`'s `toEntity()` parses it back to `Int`.
 * `status` is the lowercase wire value (`"processing"`/`"ready"`/`"failed"`), not
 * [fr.enry.archivist.data.local.db.AssetStatus]'s own uppercase enum names. */
@Serializable
data class TimelineEntryDto(
    val photoId: String,
    val takenAt: String,
    val thumbs: Map<String, fr.enry.archivist.data.local.db.ThumbEntry>,
    val encDek: String,
    val encKeyId: String,
    val width: Int,
    val height: Int,
    val mime: String,
    val tzOffsetMin: Int,
    val status: String,
)

@Serializable
data class PhotosPageResponse(val items: List<TimelineEntryDto>, val cursor: String? = null)

/** The subset of `MetaItem` (`src/core/items.ts`) plan step 2.12 needs — see
 * [ArchivistApi.getPhoto]'s doc for why this doesn't declare the item's full field set.
 * [exifEnc]/[exifIv] are absent for an asset that had no EXIF worth encrypting
 * ([fr.enry.archivist.domain.ExifBlob.from] returns null in that case), which is
 * exactly the "photo lacking EXIF" case plan step 2.12's "Done when" names. */
@Serializable
data class PhotoMetaDto(
    val photoId: String,
    val primaryRend: String? = null,
    val mime: String,
    val width: Int,
    val height: Int,
    val encDek: String,
    val encKeyId: String,
    val takenAt: String,
    val tzOffsetMin: Int,
    val takenAtSrc: String,
    val exifEnc: String? = null,
    val exifIv: String? = null,
)

/** The subset of `RenditionItem` (`src/core/items.ts`) plan step 2.12 needs. [s3Key]
 * already carries the `raw/` prefix `strip_media_prefix` (terraform/cloudfront.tf)
 * strips at the edge, so `"$apiBase-less-host/media/$s3Key"` (see
 * [fr.enry.archivist.data.repo.PhotoDetailRepository]) is the exact CloudFront URL —
 * same relationship [fr.enry.archivist.crypto.EncryptedThumbRef.url] has to `th/` keys. */
@Serializable
data class RenditionDto(
    val renditionId: String,
    val role: String,
    val ext: String,
    val mime: String,
    val s3Key: String,
    val bytes: Long,
    val plainBytes: Long,
    val width: Int,
    val height: Int,
    val encIv: String? = null,
    val encChunkSize: Long,
)

@Serializable
data class PhotoDetailResponse(val meta: PhotoMetaDto, val renditions: List<RenditionDto>)

/** One `GET /trash` entry (`routes/photos.ts`'s `getTrash`) — the same shape as
 * [TimelineEntryDto] plus, when the asset's primary rendition has a `HASH` pointer with
 * a non-zero `blockedAttempts`, the three fields that back plan step 2.13's warning:
 * *"N attempts to re-upload this from &lt;lastAttemptBy&gt; — delete it there too, or it
 * returns."* All three are absent together (never partially) — see `getTrash`'s own
 * `if (!ptr?.blockedAttempts) return dto` short-circuit. */
@Serializable
data class TrashEntryDto(
    val photoId: String,
    val takenAt: String,
    val thumbs: Map<String, fr.enry.archivist.data.local.db.ThumbEntry>,
    val encDek: String,
    val encKeyId: String,
    val width: Int,
    val height: Int,
    val mime: String,
    val tzOffsetMin: Int,
    val status: String,
    val blockedAttempts: Int? = null,
    val lastAttemptAt: String? = null,
    val lastAttemptBy: String? = null,
)

@Serializable
data class TrashPageResponse(val items: List<TrashEntryDto>, val cursor: String? = null)

/** One `D#<deviceKey>` item (`GET /devices` in api.md, `DeviceItem` in `items.ts`).
 * [tzOffsetMin] absent means "no default set" — same convention as the wire item
 * itself, per design.md's "Device config items". */
@Serializable
data class DeviceDto(
    val deviceKey: String,
    val label: String,
    val tzOffsetMin: Int? = null,
    val firstSeenAt: String,
    val photoCount: Int,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceDto>)

/** Deliberately no default values on either field — see [ArchivistApi.patchDevice]'s
 * doc for why: a defaulted `tzOffsetMin: Int? = null` would make kotlinx.serialization
 * drop it from the request body whenever the caller's value is `null` (its own
 * declared default), making "clear the default" indistinguishable from "leave it
 * alone" on the wire. Both fields are therefore required, and
 * [fr.enry.archivist.data.repo.DeviceRepository] always supplies both from its current
 * view of the device rather than a partial patch. */
@Serializable
data class PatchDeviceRequest(val label: String, val tzOffsetMin: Int?)

/** `DELETE /account` in api.md — the caller must echo back its own ownerId as an
 * explicit "type it to confirm". */
@Serializable
data class DeleteAccountRequest(val confirmOwnerId: String)

@Serializable
data class OriginalUploadDto(val url: String)

/** Every shape `postUpload` (server-side) can return, collapsed into one class with
 * variant-specific fields left null — same pattern as [KeyWrapDto]. See
 * `routes/uploads.ts` for which fields are set together:
 * - `duplicate`/`trashed`/`restored`/`skipped` — no upload needed, nothing to encrypt.
 * - `created`/`resumed`/`encDek`/`encKeyId`/`originalUpload`/`thumbUploads` — proceed;
 *   see [fr.enry.archivist.data.repo.UploadRepository] for exactly how the three
 *   combinations of `created`/`resumed` change what gets (re-)encrypted and PUT. */
@Serializable
data class PostUploadResponse(
    val photoId: String? = null,
    val renditionId: String? = null,
    val duplicate: Boolean? = null,
    val trashed: Boolean? = null,
    val restored: Boolean? = null,
    val skipped: Boolean? = null,
    val created: Boolean? = null,
    val resumed: Boolean? = null,
    val encDek: String? = null,
    val encKeyId: String? = null,
    /** Set only when [resumed] is true — the rendition's own `encIv`/`encChunkSize`
     * were fixed by whichever attempt's transaction actually committed and are never
     * rewritten afterwards, so a resuming client must reuse them exactly rather than
     * generating fresh ones (see "Resuming an interrupted upload" in design.md). */
    val encIv: String? = null,
    val encChunkSize: Long? = null,
    val originalUpload: OriginalUploadDto? = null,
    val thumbUploads: Map<String, String>? = null,
)
