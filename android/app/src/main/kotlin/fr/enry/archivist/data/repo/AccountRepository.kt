package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.AppDatabase
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.DeleteAccountRequest
import fr.enry.archivist.data.remote.SessionBootstrapRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Plan step 2.14's Settings > Account section — sign-out lives on
 * [AuthRepository.signOut] already; this class is the one action that's new:
 * `DELETE /account` (api.md), which erases the owner's entire library server-side.
 *
 * The app doesn't otherwise know its own `ownerId` (nothing before this step ever
 * needed it client-side — the JWT alone identifies the caller for every other route),
 * so [deleteAccount] re-calls `POST /session/bootstrap` first purely to read it back.
 * That call is already idempotent (plan step 1.7's own "Done when"), so doing it a
 * second time here is free of side effects.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val authRepository: AuthRepository,
        private val enrolmentStore: EnrolmentStore,
        private val masterKeyHolder: MasterKeyHolder,
        private val hashSecretHolder: HashSecretHolder,
        private val appDatabase: AppDatabase,
    ) {
        suspend fun deleteAccount(): Result<Unit> {
            val instance = instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val apiBase = instance.document.apiBase

            return try {
                val ownerId = api.postSessionBootstrap("$apiBase/session/bootstrap", SessionBootstrapRequest()).ownerId
                val response = api.deleteAccount("$apiBase/account", DeleteAccountRequest(confirmOwnerId = ownerId))
                if (!response.isSuccessful) return Result.failure(HttpException(response))

                // The server-side wrapping/library is gone; forget everything this
                // device believed about it rather than leaving stale local state that
                // would only confuse a future sign-in (as this owner or a different
                // one) against the same instance.
                masterKeyHolder.clear()
                hashSecretHolder.clear()
                enrolmentStore.clearDeviceWrapId(instance.host)
                enrolmentStore.clearCachedDeviceWrap(instance.host)
                // RoomDatabase.clearAllTables() asserts it's never called from the
                // main thread (it can block for a while) -- this whole function would
                // otherwise run on viewModelScope's default Dispatchers.Main.immediate,
                // which is exactly that thread.
                withContext(Dispatchers.IO) { appDatabase.clearAllTables() }
                authRepository.signOut()
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }
    }
