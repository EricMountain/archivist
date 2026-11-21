package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.repo.RegionTileCache
import fr.enry.archivist.data.repo.StorageRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(
    val cacheSizeBytes: Long? = null,
    val isClearing: Boolean = false,
    val placeDataBytes: Long? = null,
    val isClearingPlaceData: Boolean = false,
)

/** Plan step 2.14's Settings > Storage section — "thumbnail cache size and a
 * clear-cache action". See [StorageRepository]'s own doc for which cache this is. Also
 * the region tiles ([RegionTileCache], design.md "Detailed places"). */
@HiltViewModel
class StorageViewModel
    @Inject
    constructor(
        private val storageRepository: StorageRepository,
        private val regionTiles: RegionTileCache,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(StorageUiState())
        val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        cacheSizeBytes = storageRepository.cacheSizeBytes(),
                        placeDataBytes = regionTiles.sizeBytes(),
                    )
            }
        }

        fun clearCache() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isClearing = true)
                storageRepository.clearCache()
                _uiState.value = _uiState.value.copy(cacheSizeBytes = storageRepository.cacheSizeBytes(), isClearing = false)
            }
        }

        fun clearPlaceData() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isClearingPlaceData = true)
                regionTiles.clear()
                _uiState.value = _uiState.value.copy(placeDataBytes = regionTiles.sizeBytes(), isClearingPlaceData = false)
            }
        }
    }
