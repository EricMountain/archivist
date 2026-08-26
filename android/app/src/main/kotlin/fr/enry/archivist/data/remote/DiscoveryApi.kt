package fr.enry.archivist.data.remote

import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit is configured with a placeholder base URL (see [NetworkModule]) since the
 * real host isn't known until a user types one in; every call here supplies its own
 * absolute [Url] instead.
 */
interface DiscoveryApi {
    @GET
    suspend fun getDiscoveryDocument(@Url url: String): DiscoveryDocument
}
