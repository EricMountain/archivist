package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.StoredInstance
import fr.enry.archivist.data.remote.DiscoveryClient
import fr.enry.archivist.data.remote.DiscoveryResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

sealed interface ConnectOutcome {
    data class Connected(val instanceName: String) : ConnectOutcome

    data object InvalidHost : ConnectOutcome

    data object HostNotFound : ConnectOutcome

    data object NotArchivist : ConnectOutcome

    data class ServerTooNew(val serverVersion: Int) : ConnectOutcome
}

/** The only thing that knows an instance's config comes from discovery + DataStore. */
class InstanceRepository
    @Inject
    constructor(
        private val discoveryClient: DiscoveryClient,
        private val instanceStore: InstanceStore,
    ) {
        val currentInstance: Flow<StoredInstance?> = instanceStore.current
        val reviewerPreviewEnabled: Flow<Boolean> = instanceStore.reviewerPreviewEnabled

        suspend fun enterReviewerPreview() = instanceStore.setReviewerPreviewEnabled(true)

        suspend fun exitReviewerPreview() = instanceStore.setReviewerPreviewEnabled(false)

        suspend fun connect(hostInput: String): ConnectOutcome =
            when (val result = discoveryClient.fetch(hostInput)) {
                is DiscoveryResult.Success -> {
                    instanceStore.save(result.host, result.document)
                    ConnectOutcome.Connected(result.document.instanceName)
                }
                DiscoveryResult.InvalidHost -> ConnectOutcome.InvalidHost
                DiscoveryResult.HostNotFound -> ConnectOutcome.HostNotFound
                DiscoveryResult.NotArchivist -> ConnectOutcome.NotArchivist
                is DiscoveryResult.ServerTooNew -> ConnectOutcome.ServerTooNew(result.serverVersion)
            }
    }
