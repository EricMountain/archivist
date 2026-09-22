package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.TimelineKey
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PatchTakenAtRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/** What [TakenAtRepository.correct] produced, for
 * [fr.enry.archivist.ui.detail.DetailViewModel] to act on. */
sealed interface TakenAtOutcome {
    data object Done : TakenAtOutcome

    data class Error(val message: String) : TakenAtOutcome
}

/**
 * The in-app "edit date" action — `PATCH /photos/{photoId}` (api.md, design.md
 * "Manually correcting takenAt"). Unlike [RepairRepository], this genuinely does move
 * the photo's position in the timeline (that's the whole point of correcting a
 * timestamp), but still uses [TimelineJumpCoordinator.stageExternalRefreshKey] rather
 * than a jump landing — that coordinator's own doc scopes a jump landing to "the user
 * asked to go here", which isn't true of a correction made from the detail pager; the
 * user's own swipe position shouldn't visibly relocate just because they fixed a date.
 * No crypto involved: `takenAt`/`tzOffsetMin` are plaintext server-side fields, unlike
 * [RepairRepository]'s thumbnails.
 */
@Singleton
class TakenAtRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val photoDao: PhotoDao,
        private val jumpCoordinator: TimelineJumpCoordinator,
    ) {
        suspend fun correct(
            photoId: String,
            takenAt: String,
            tzOffsetMin: Int,
        ): TakenAtOutcome {
            val instance = instanceStore.current.first() ?: return TakenAtOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            val apiBase = instance.document.apiBase

            val response =
                try {
                    api.patchTakenAt(photoUrl(apiBase, photoId), PatchTakenAtRequest(takenAt, tzOffsetMin))
                } catch (e: IOException) {
                    return TakenAtOutcome.Error(e.message ?: "network error")
                } catch (e: HttpException) {
                    return TakenAtOutcome.Error("HTTP ${e.code()}")
                }
            if (!response.isSuccessful) return TakenAtOutcome.Error("HTTP ${response.code()}")

            // Same reasoning as RepairRepository's own refetch: rather than patching
            // photoDao's row from the values just sent (which would also need takenAtSrc/
            // tzSrc recomputed to match what the server actually set), refetch the
            // authoritative row and stage it as an external refresh -- see this class's
            // own doc for why that's a refresh key, not a jump landing.
            val refreshed = api.getPhotoAsTimelineEntry(photoUrl(apiBase, photoId))
            jumpCoordinator.stageExternalRefreshKey(TimelineKey(refreshed.meta.takenAt, refreshed.meta.photoId))
            photoDao.upsertAll(listOf(refreshed.meta.toEntity()))

            return TakenAtOutcome.Done
        }
    }

private fun photoUrl(
    apiBase: String,
    photoId: String,
) = "$apiBase/photos/$photoId"
