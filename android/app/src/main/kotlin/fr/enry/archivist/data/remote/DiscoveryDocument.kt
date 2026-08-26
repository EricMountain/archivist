package fr.enry.archivist.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `GET /.well-known/archivist.json` body — see "The app must find the backend"
 * in deployment.md. Served publicly and unauthenticated, so nothing here is secret;
 * it's just the coordinates needed to attempt a login against this instance.
 */
@Serializable
data class DiscoveryDocument(
    val apiBase: String,
    val region: String,
    val cognito: CognitoConfig,
    val cryptoVersion: Int,
    val instanceName: String,
) {
    @Serializable
    data class CognitoConfig(
        val userPoolId: String,
        val clientId: String,
    )
}

/**
 * The crypto-format.md version this build knows how to read. `cryptoVersion` in the
 * discovery document is compared against this at connect time — see plan step 2.3 —
 * so an instance the app is too old for is refused up front, not three screens later
 * as a decryption failure.
 */
@Suppress("ConstPropertyName")
const val SUPPORTED_CRYPTO_VERSION: Int = 1

/**
 * `<host>/.well-known/archivist.json`, resolved by [DiscoveryClient] into an
 * absolute URL — Retrofit is otherwise configured with a placeholder base URL since
 * the real host is only known per connection attempt, never at build time.
 */
internal fun discoveryUrl(host: String): String = "https://$host/.well-known/archivist.json"
