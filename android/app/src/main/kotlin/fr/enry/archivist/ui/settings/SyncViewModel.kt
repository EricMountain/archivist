package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.UploadQueueDao
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
        uploadQueueDao: UploadQueueDao,
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
    }
