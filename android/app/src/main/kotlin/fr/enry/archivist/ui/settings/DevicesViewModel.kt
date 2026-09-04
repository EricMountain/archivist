package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.db.DeviceEntity
import fr.enry.archivist.data.repo.DeviceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DevicesUiState {
    data object Loading : DevicesUiState

    data class Loaded(val devices: List<DeviceEntity>, val error: String? = null) : DevicesUiState
}

/**
 * Plan step 2.14's Settings > Devices section — the camera `deviceKey` config items
 * (design.md's "Device config items"), not the Keys section's enrolled-device
 * wrappings. See [DeviceRepository]'s own doc for the local-cache/server split.
 */
@HiltViewModel
class DevicesViewModel
    @Inject
    constructor(
        private val deviceRepository: DeviceRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
        val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                deviceRepository.refresh().fold(
                    onSuccess = { _uiState.value = DevicesUiState.Loaded(it) },
                    onFailure = {
                        _uiState.value =
                            DevicesUiState.Loaded(deviceRepository.cached(), error = "Couldn't refresh — showing the last known list.")
                    },
                )
            }
        }

        fun update(
            deviceKey: String,
            label: String,
            tzOffsetMin: Int?,
        ) {
            viewModelScope.launch {
                val result = deviceRepository.update(deviceKey, label, tzOffsetMin)
                if (result.isFailure) {
                    _uiState.value = (_uiState.value as? DevicesUiState.Loaded)?.copy(error = "Couldn't save — try again.") ?: return@launch
                    return@launch
                }
                refresh()
            }
        }

        fun remove(deviceKey: String) {
            viewModelScope.launch {
                val result = deviceRepository.remove(deviceKey)
                if (result.isFailure) {
                    _uiState.value = (_uiState.value as? DevicesUiState.Loaded)?.copy(error = "Couldn't remove — try again.") ?: return@launch
                    return@launch
                }
                refresh()
            }
        }
    }
