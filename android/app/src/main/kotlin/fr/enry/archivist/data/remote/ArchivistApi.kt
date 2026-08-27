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

/** The generic `{"error": "..."}` shape every non-`ApiError` response from the
 * Archivist API (not Cognito's) uses — see `src/lambda/api/index.ts`. */
@Serializable
data class ArchivistErrorBody(val error: String? = null)
