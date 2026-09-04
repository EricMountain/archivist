package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.repo.StorageRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(val cacheSizeBytes: Long? = null, val isClearing: Boolean = false)

/** Plan step 2.14's Settings > Storage section — "thumbnail cache size and a
 * clear-cache action". See [StorageRepository]'s own doc for which cache this is. */
@HiltViewModel
class StorageViewModel
    @Inject
    constructor(
        private val storageRepository: StorageRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(StorageUiState())
        val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(cacheSizeBytes = storageRepository.cacheSizeBytes())
            }
        }

        fun clearCache() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isClearing = true)
                storageRepository.clearCache()
                _uiState.value = StorageUiState(cacheSizeBytes = storageRepository.cacheSizeBytes(), isClearing = false)
            }
        }
    }
