package fr.enry.archivist.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The instance's own metadata API — `apiBase` from the discovery document, JWT
 * bearer-authenticated via [ArchivistAuthInterceptor]/[ArchivistAuthenticator]. Only
 * `postSessionBootstrap` exists yet; later plan steps (2.6+) add the rest of
 * `docs/design/api.md`'s route table here.
 */
interface ArchivistApi {
    @POST
    suspend fun postSessionBootstrap(
        @Url url: String,
        @Body body: SessionBootstrapRequest,
    ): SessionBootstrapResponse
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
