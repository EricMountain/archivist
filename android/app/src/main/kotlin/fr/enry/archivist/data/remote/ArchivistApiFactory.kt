package fr.enry.archivist.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.enry.archivist.data.local.AuthSession
import fr.enry.archivist.data.local.TokenStore
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit

/**
 * Attaches the current session's access token to every request. Does nothing for a
 * host with no session — those requests reach the API unauthenticated and get a
 * clean `401` from the JWT authorizer, same as any other unauthenticated call.
 */
private class ArchivistAuthInterceptor(
    private val host: String,
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = tokenStore.get(host)
        val request =
            if (session != null) {
                chain.request().newBuilder().header("Authorization", "Bearer ${session.accessToken}").build()
            } else {
                chain.request()
            }
        return chain.proceed(request)
    }
}

/**
 * Refreshes on `401` exactly once, per plan step 2.4. `@Synchronized` (one instance
 * per host, from [ArchivistApiFactory]) makes this also the guard against two
 * concurrent requests both refreshing: the second one to arrive re-checks the stored
 * token before calling Cognito again, and reuses whatever the first one just wrote.
 */
private class ArchivistAuthenticator(
    private val host: String,
    private val region: String,
    private val clientId: String,
    private val tokenStore: TokenStore,
    private val cognitoAuthClient: CognitoAuthClient,
) : Authenticator {
    @Synchronized
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        if (responseChainLength(response) >= 2) return null // never retry more than once

        val session = tokenStore.get(host) ?: return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        if (session.accessToken != failedToken) {
            // Another request already refreshed while this one waited on the lock.
            return response.request.newBuilder().header("Authorization", "Bearer ${session.accessToken}").build()
        }

        val refreshed = runBlocking { cognitoAuthClient.refresh(region, clientId, session.refreshToken) }
        val newSession =
            when (refreshed) {
                is CognitoAuthResult.SignedIn ->
                    AuthSession(
                        username = session.username,
                        accessToken = refreshed.result.accessToken,
                        idToken = refreshed.result.idToken,
                        // Cognito doesn't rotate refresh tokens on REFRESH_TOKEN_AUTH by
                        // default — confirmed live — so keep the existing one when absent.
                        refreshToken = refreshed.result.refreshToken ?: session.refreshToken,
                        accessTokenExpiresAt = System.currentTimeMillis() + refreshed.result.expiresIn * 1000L,
                    )
                else -> {
                    tokenStore.clear(host)
                    return null
                }
            }
        tokenStore.save(host, newSession)
        return response.request.newBuilder().header("Authorization", "Bearer ${newSession.accessToken}").build()
    }

    private fun responseChainLength(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

private const val PLACEHOLDER_BASE_URL = "https://placeholder.example/"

/**
 * Builds an [ArchivistApi] bound to one instance. Not a Hilt singleton: which host
 * is "current" isn't known until [fr.enry.archivist.data.repo.InstanceRepository]
 * resolves it, so this is a factory [AuthRepository][fr.enry.archivist.data.repo.AuthRepository]
 * calls once it has, rather than something built eagerly at app start.
 */
class ArchivistApiFactory
    @Inject
    constructor(
        private val baseOkHttpClient: OkHttpClient,
        private val json: Json,
        private val tokenStore: TokenStore,
        private val cognitoAuthClient: CognitoAuthClient,
    ) {
        fun create(
            host: String,
            region: String,
            clientId: String,
        ): ArchivistApi {
            val client =
                baseOkHttpClient.newBuilder()
                    .addInterceptor(ArchivistAuthInterceptor(host, tokenStore))
                    .authenticator(ArchivistAuthenticator(host, region, clientId, tokenStore, cognitoAuthClient))
                    .build()
            val retrofit =
                Retrofit.Builder()
                    .baseUrl(PLACEHOLDER_BASE_URL)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return retrofit.create(ArchivistApi::class.java)
        }
    }
