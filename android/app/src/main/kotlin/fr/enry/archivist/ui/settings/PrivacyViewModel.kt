package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.repo.OwnerSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PrivacyUiState {
    data object Loading : PrivacyUiState

    data class Loaded(
        val stripLocationOnUpload: Boolean,
        val isSaving: Boolean = false,
        val error: String? = null,
    ) : PrivacyUiState
}

/**
 * Plan step 2.18's Settings > Privacy section — one switch, owner-level and
 * server-synced via [OwnerSettingsRepository] (`GET`/`PATCH /settings`), unlike Sync's
 * per-device toggles which never leave this phone. See "Stripping location on upload"
 * in design.md for why this setting specifically shouldn't be per-device.
 */
@HiltViewModel
class PrivacyViewModel
    @Inject
    constructor(
        private val ownerSettingsRepository: OwnerSettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<PrivacyUiState>(PrivacyUiState.Loading)
        val uiState: StateFlow<PrivacyUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _uiState.value = PrivacyUiState.Loading
                ownerSettingsRepository.stripLocationOnUpload().fold(
                    onSuccess = { _uiState.value = PrivacyUiState.Loaded(it) },
                    onFailure = {
                        _uiState.value =
                            PrivacyUiState.Loaded(stripLocationOnUpload = false, error = "Couldn't load — try again.")
                    },
                )
            }
        }

        /** Optimistic: the switch flips immediately, and reverts to [current]'s value
         * on failure rather than leaving the UI showing a state the server never
         * actually accepted. */
        fun setStripLocationOnUpload(value: Boolean) {
            val current = _uiState.value as? PrivacyUiState.Loaded ?: return
            viewModelScope.launch {
                _uiState.value = current.copy(stripLocationOnUpload = value, isSaving = true, error = null)
                ownerSettingsRepository.setStripLocationOnUpload(value).fold(
                    onSuccess = { _uiState.value = PrivacyUiState.Loaded(value) },
                    onFailure = {
                        _uiState.value = current.copy(isSaving = false, error = "Couldn't save — try again.")
                    },
                )
            }
        }
    }
