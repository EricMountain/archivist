package fr.enry.archivist.data.remote

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Outcome of attempting to connect to an instance — kept distinct rather than a
 * single "connection failed" because a typo, a non-Archivist host and a too-new
 * server all need different UI copy. See "Instance connection" in
 * `docs/plans/02-android-mvp.md`.
 */
sealed interface DiscoveryResult {
    data class Success(val host: String, val document: DiscoveryDocument) : DiscoveryResult

    /** Empty input, or an explicit non-https scheme (notably `http://`). */
    data object InvalidHost : DiscoveryResult

    /** DNS failure, connection refused, timeout, TLS failure — nothing answered. */
    data object HostNotFound : DiscoveryResult

    /** Something answered, but not with a valid discovery document. */
    data object NotArchivist : DiscoveryResult

    /** A valid discovery document, but for a crypto format newer than this build reads. */
    data class ServerTooNew(val serverVersion: Int) : DiscoveryResult
}

class DiscoveryClient @Inject constructor(
    private val api: DiscoveryApi,
) {
    suspend fun fetch(hostInput: String): DiscoveryResult {
        val host = normalizeHost(hostInput) ?: return DiscoveryResult.InvalidHost

        val document =
            try {
                api.getDiscoveryDocument(discoveryUrl(host))
            } catch (e: IOException) {
                return DiscoveryResult.HostNotFound
            } catch (e: HttpException) {
                return DiscoveryResult.NotArchivist
            } catch (e: SerializationException) {
                return DiscoveryResult.NotArchivist
            }

        return if (document.cryptoVersion > SUPPORTED_CRYPTO_VERSION) {
            DiscoveryResult.ServerTooNew(document.cryptoVersion)
        } else {
            DiscoveryResult.Success(host, document)
        }
    }
}

/**
 * No default, no fallback, HTTPS only (plan step 2.3): bare input is assumed https,
 * an explicit `https://` prefix is accepted and stripped, and any other scheme —
 * `http://` above all — is refused outright rather than silently upgraded.
 */
internal fun normalizeHost(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withoutScheme =
        when {
            trimmed.startsWith("https://") -> trimmed.removePrefix("https://")
            trimmed.contains("://") -> return null
            else -> trimmed
        }
    val host = withoutScheme.substringBefore("/").trim()
    return host.ifEmpty { null }
}
