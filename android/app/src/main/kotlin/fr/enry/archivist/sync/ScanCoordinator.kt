package fr.enry.archivist.sync

import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.repo.EnrolmentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ScanOutcome {
    /** [count] is how many new files this scan queued; every unfinished row has been
     * handed to the [UploadScheduler] either way. */
    data class Queued(val count: Int) : ScanOutcome

    /** No hash secret (locked, signed out, or offline on first fetch) -- nothing was scanned. */
    data object HashSecretUnavailable : ScanOutcome

    data object Failed : ScanOutcome
}

/**
 * "Scan the selected folders, then upload whatever's queued" as one step, shared by the
 * two things that trigger it: the user (Settings > Sync > Folders, via
 * [fr.enry.archivist.ui.settings.FoldersViewModel]) and [ScanWorker] (a MediaStore
 * change, app start, or the periodic backstop). A singleton with a [Mutex] so those
 * can't run overlapping scans of the same folders.
 */
@Singleton
class ScanCoordinator
    @Inject
    constructor(
        private val scanner: Scanner,
        private val enrolmentRepository: EnrolmentRepository,
        private val uploadQueueDao: UploadQueueDao,
        private val uploadScheduler: UploadScheduler,
    ) {
        private val mutex = Mutex()

        suspend fun scanAndEnqueue(): ScanOutcome =
            mutex.withLock {
                if (enrolmentRepository.ensureHashSecret().isFailure) return@withLock ScanOutcome.HashSecretUnavailable
                val result = scanner.scan()
                // Enqueue every unfinished row, not just what this scan added, so a
                // previous scan's leftovers (e.g. from before the app had a master key)
                // get picked up too.
                if (result.isFailure) return@withLock ScanOutcome.Failed
                uploadScheduler.enqueueAll(uploadQueueDao.getActiveIds())
                ScanOutcome.Queued(result.getOrThrow())
            }
    }
