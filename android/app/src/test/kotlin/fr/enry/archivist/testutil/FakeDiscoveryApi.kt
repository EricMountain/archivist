package fr.enry.archivist.testutil

import fr.enry.archivist.data.remote.DiscoveryApi
import fr.enry.archivist.data.remote.DiscoveryDocument

/** Test double for [DiscoveryApi]: either returns a canned document or throws a
 * canned exception, and records the last URL it was called with. */
class FakeDiscoveryApi : DiscoveryApi {
    var response: (suspend (url: String) -> DiscoveryDocument)? = null
    var error: Throwable? = null
    var lastUrl: String? = null
        private set

    override suspend fun getDiscoveryDocument(url: String): DiscoveryDocument {
        lastUrl = url
        error?.let { throw it }
        return response?.invoke(url) ?: error("FakeDiscoveryApi not configured for this call")
    }
}
