package fr.enry.archivist.ui.reviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.sync.DeviceFolder
import fr.enry.archivist.sync.MediaStoreSource
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the reviewer preview's "Sync" section (`ReviewerSettingsScreen`) — real device
 * folder names, read the same way [ReviewerPreviewViewModel] reads photos, so the
 * Folders list a reviewer sees isn't fabricated. Same guarantee as that class: only
 * [MediaStoreSource] in the constructor, nothing network-capable.
 */
@HiltViewModel
class ReviewerSettingsViewModel
    @Inject
    constructor(
        private val mediaStoreSource: MediaStoreSource,
    ) : ViewModel() {
        private val _folders = MutableStateFlow<List<DeviceFolder>>(emptyList())
        val folders: StateFlow<List<DeviceFolder>> = _folders.asStateFlow()

        init {
            viewModelScope.launch {
                _folders.value = mediaStoreSource.listFolders()
            }
        }
    }
