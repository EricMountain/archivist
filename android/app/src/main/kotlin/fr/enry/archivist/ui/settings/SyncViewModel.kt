package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.SyncSettingsStore
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
    ) : ViewModel() {
        val settings: StateFlow<SyncSettings> =
            store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

        fun setAllowMeteredNetwork(allow: Boolean) {
            viewModelScope.launch { store.setAllowMeteredNetwork(allow) }
        }

        fun setRequiresCharging(requires: Boolean) {
            viewModelScope.launch { store.setRequiresCharging(requires) }
        }
    }
