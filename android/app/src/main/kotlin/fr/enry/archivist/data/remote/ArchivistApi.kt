package fr.enry.archivist.data.remote

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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
