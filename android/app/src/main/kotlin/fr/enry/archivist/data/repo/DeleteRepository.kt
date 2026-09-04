package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.LocalTombstoneDao
import fr.enry.archivist.data.local.db.LocalTombstoneEntity
import fr.enry.archivist.data.local.db.PhotoDao
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.sync.MediaDeleteOutcome
import fr.enry.archivist.sync.MediaStoreSource
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/** Which of the three-way prompt's non-cancel options the user picked — see
 * `docs/design/android.md`'s "Deleting on the phone". */
enum class DeleteMode {
    ARCHIVE_ONLY,
    BOTH,
}

/** What [DeleteRepository.delete] produced, for [fr.enry.archivist.ui.detail.DetailViewModel]
 * to act on. [NeedsMediaConfirmation] only ever follows a [DeleteMode.BOTH] request on
 * API 30+ (or a file this app doesn't own on API 29) — the archive-side delete has
 * already happened by the time this is returned, so the caller only needs to launch the
 * confirmation and, on a successful result, call [DeleteRepository.finishMediaDelete]. */
sealed interface DeleteOutcome {
    data object Done : DeleteOutcome

    data class NeedsMediaConfirmation(val intentSender: android.content.IntentSender, val photoId: String) : DeleteOutcome

    data class Error(val message: String) : DeleteOutcome
}

/**
 * Plan step 2.13. Orchestrates the three-way delete prompt's two real actions —
 * `DELETE /photos/{photoId}` and, for [DeleteMode.BOTH], a `MediaStore` removal — plus
 * the two local bookkeeping steps `android.md` calls out as required regardless of
 * which option was chosen:
 *
 * * The photo's Room row is deleted immediately so it vanishes from the timeline without
 *   waiting on a `GET /photos` refresh to notice it moved to the trash partition.
 * * A [LocalTombstoneEntity] is written for every rendition of this asset that this
 *   device has itself uploaded (found via [UploadQueueDao.getByPhotoId] — the
 *   `renditions` Room table is never populated by the upload path, see plan step 2.6/
 *   2.10's STATUS.md notes, so `upload_queue` is the only local record tying a photoId
 *   back to a contentHash), so [fr.enry.archivist.sync.Scanner] doesn't re-queue the file
 *   still sitting on the phone on its next pass. Written *before* attempting any
 *   `MediaStore` deletion and regardless of how that turns out: if the user picked "both"
 *   but then denies or the confirmation fails, the end state is identical to "archive
 *   only" (file stays, must not be re-uploaded), so the same tombstone is exactly right
 *   either way.
 */
@Singleton
class DeleteRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val photoDao: PhotoDao,
        private val uploadQueueDao: UploadQueueDao,
        private val localTombstoneDao: LocalTombstoneDao,
        private val mediaStoreSource: MediaStoreSource,
    ) {
        suspend fun delete(
            photoId: String,
            mode: DeleteMode,
        ): DeleteOutcome {
            val instance = instanceStore.current.first() ?: return DeleteOutcome.Error("no connected instance")
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)

            val response =
                try {
                    api.deletePhoto("${instance.document.apiBase}/photos/$photoId")
                } catch (e: IOException) {
                    return DeleteOutcome.Error(e.message ?: "network error")
                } catch (e: HttpException) {
                    return DeleteOutcome.Error("HTTP ${e.code()}")
                }
            if (!response.isSuccessful) return DeleteOutcome.Error("HTTP ${response.code()}")

            photoDao.deleteByPhotoId(photoId)
            tombstoneLocalFiles(photoId)

            if (mode == DeleteMode.ARCHIVE_ONLY) return DeleteOutcome.Done

            val localUris = uploadQueueDao.getByPhotoId(photoId).map { it.localUri }
            return when (val outcome = mediaStoreSource.requestDelete(localUris)) {
                MediaDeleteOutcome.Deleted -> {
                    forgetQueueRows(photoId)
                    DeleteOutcome.Done
                }
                is MediaDeleteOutcome.NeedsConfirmation -> DeleteOutcome.NeedsMediaConfirmation(outcome.intentSender, photoId)
                is MediaDeleteOutcome.Failed -> DeleteOutcome.Error(outcome.message)
            }
        }

        /** Called once the caller's `StartIntentSenderForResult` launch for a
         * [DeleteOutcome.NeedsMediaConfirmation] comes back `RESULT_OK` — approving that
         * confirmation is itself what performs the deletion (see [MediaDeleteOutcome]'s
         * own doc), so this only needs to clean up the now-stale `upload_queue` rows. */
        suspend fun finishMediaDelete(photoId: String) = forgetQueueRows(photoId)

        private suspend fun tombstoneLocalFiles(photoId: String) {
            val now = Instant.now().toString()
            uploadQueueDao.getByPhotoId(photoId).forEach { row ->
                row.contentHash?.let { hash -> localTombstoneDao.upsert(LocalTombstoneEntity(hash, now)) }
            }
        }

        private suspend fun forgetQueueRows(photoId: String) {
            uploadQueueDao.getByPhotoId(photoId).forEach { uploadQueueDao.deleteById(it.id) }
        }
    }
