package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.sync.UploadScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plan step 2.14's Settings > Sync section — network policy and charging requirement.
 * Folder selection itself is [FoldersScreen]/[FoldersViewModel], hosted alongside these
 * two toggles rather than duplicated here.
 */
@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val store: SyncSettingsStore,
        private val uploadQueueDao: UploadQueueDao,
        private val uploadScheduler: UploadScheduler,
    ) : ViewModel() {
        val settings: StateFlow<SyncSettings> =
            store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

        /** Same count as the upload notification's content text
         * ([fr.enry.archivist.sync.UploadWorker.buildProgressNotification]) — how many
         * photos are still queued, whatever their state short of DONE. */
        val queueDepth: StateFlow<Int> =
            uploadQueueDao.observeRemainingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

        fun setAllowMeteredNetwork(allow: Boolean) {
            viewModelScope.launch { store.setAllowMeteredNetwork(allow) }
        }

        fun setRequiresCharging(requires: Boolean) {
            viewModelScope.launch { store.setRequiresCharging(requires) }
        }

        fun setNotifyWhenUploadNeedsUnlock(notify: Boolean) {
            viewModelScope.launch { store.setNotifyWhenUploadNeedsUnlock(notify) }
        }

        fun setShowUploadProgressNotification(show: Boolean) {
            viewModelScope.launch { store.setShowUploadProgressNotification(show) }
        }

        fun setUploadAsForegroundService(foreground: Boolean) {
            viewModelScope.launch { store.setUploadAsForegroundService(foreground) }
        }

        /** Turning this on cancels whatever's enqueued or running right now
         * ([UploadScheduler.cancelAll]) without touching `upload_queue` -- the rows
         * stay exactly where they were. Turning it off is what actually resumes them:
         * [UploadScheduler.enqueueAll] is the seam that reads this same setting and
         * declines to schedule anything while it's on, so nothing restarts on its own
         * the moment the flag flips -- this re-enqueues [UploadQueueDao.getActiveIds]
         * explicitly, the same call app startup and a fresh scan already make. */
        fun setUploadsPaused(paused: Boolean) {
            viewModelScope.launch {
                store.setUploadsPaused(paused)
                if (paused) {
                    uploadScheduler.cancelAll()
                } else {
                    uploadScheduler.enqueueAll(uploadQueueDao.getActiveIds())
                }
            }
        }
    }
