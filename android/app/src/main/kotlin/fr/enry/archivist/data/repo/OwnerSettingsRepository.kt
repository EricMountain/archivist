package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PatchSettingsRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * `GET`/`PATCH /settings` (plan step 2.18) — owner-level policy, currently just
 * [stripLocationOnUpload]. Unlike [DeviceRepository] this is server truth for one
 * small value, not a list, so there's no local Room cache: callers read it fresh, the
 * same way [UploadRepository] already reads [InstanceStore.current] via
 * [kotlinx.coroutines.flow.first] rather than inventing a second caching strategy.
 */
@Singleton
class OwnerSettingsRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
    ) {
        /** [UploadRepository] calls this once per upload attempt — a network round
         * trip per file, deliberately, since this is owner-level policy that can
         * change between uploads and there's nothing here worth caching stale. */
        suspend fun stripLocationOnUpload(): Result<Boolean> {
            val instance =
                instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            return try {
                Result.success(api.getSettings("${instance.document.apiBase}/settings").stripLocationOnUpload)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        suspend fun setStripLocationOnUpload(value: Boolean): Result<Unit> {
            val instance =
                instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            return try {
                val response =
                    api.patchSettings("${instance.document.apiBase}/settings", PatchSettingsRequest(value))
                if (!response.isSuccessful) return Result.failure(HttpException(response))
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }
    }
