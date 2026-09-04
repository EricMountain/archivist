package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.crypto.RecoveryCode
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.repo.EnrolmentRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface KeysUiState {
    data object Loading : KeysUiState

    data class Loaded(val wraps: List<KeyWrapDto>, val error: String? = null) : KeysUiState
}

/** The recovery-code-regeneration sub-flow — a smaller version of
 * [fr.enry.archivist.ui.onboarding.EnrolmentUiState]'s `ShowRecoveryCode`/
 * `ConfirmRecoveryCode` pair, reusing [EnrolmentRepository]'s same
 * generate-then-confirm-before-committing shape (see its own doc on
 * [fr.enry.archivist.crypto.KeyCustody.regenerateRecoveryWrap]). */
sealed interface RecoveryRegenState {
    data object Hidden : RecoveryRegenState

    data class ShowingCode(val formattedCode: String) : RecoveryRegenState

    data class Confirming(val formattedCode: String, val isSubmitting: Boolean = false, val error: String? = null) : RecoveryRegenState
}

/**
 * Plan step 2.14's Settings > Keys section — "enrolled wrappings, re-show recovery
 * code confirmation flow". "Enrolled wrappings" here means devices/passkeys/recovery —
 * `GET /keys` — not the Devices section's camera config items, a different `device`
 * entirely.
 */
@HiltViewModel
class KeysViewModel
    @Inject
    constructor(
        private val enrolmentRepository: EnrolmentRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<KeysUiState>(KeysUiState.Loading)
        val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

        private val _regenState = MutableStateFlow<RecoveryRegenState>(RecoveryRegenState.Hidden)
        val regenState: StateFlow<RecoveryRegenState> = _regenState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                enrolmentRepository.listKeys().fold(
                    onSuccess = { _uiState.value = KeysUiState.Loaded(it) },
                    onFailure = { _uiState.value = KeysUiState.Loaded(emptyList(), error = "Couldn't load your keys — try again.") },
                )
            }
        }

        fun removeKey(wrapId: String) {
            viewModelScope.launch {
                enrolmentRepository.removeKey(wrapId).fold(
                    onSuccess = { refresh() },
                    onFailure = { e ->
                        val loaded = _uiState.value as? KeysUiState.Loaded ?: return@launch
                        _uiState.value = loaded.copy(error = e.message ?: "Couldn't remove that wrapping.")
                    },
                )
            }
        }

        fun beginRecoveryRegeneration() {
            enrolmentRepository.beginRecoveryRegeneration().fold(
                onSuccess = { regeneration ->
                    _regenState.value = RecoveryRegenState.ShowingCode(RecoveryCode.format(regeneration.recoveryCode.code))
                },
                onFailure = {
                    val loaded = _uiState.value as? KeysUiState.Loaded ?: return@fold
                    _uiState.value = loaded.copy(error = "Couldn't generate a new code right now.")
                },
            )
        }

        fun proceedToConfirmation() {
            val state = _regenState.value
            if (state is RecoveryRegenState.ShowingCode) {
                _regenState.value = RecoveryRegenState.Confirming(state.formattedCode)
            }
        }

        fun showCodeAgain() {
            val state = _regenState.value
            if (state is RecoveryRegenState.Confirming) {
                _regenState.value = RecoveryRegenState.ShowingCode(state.formattedCode)
            }
        }

        fun confirmTypedCode(typed: String) {
            val state = _regenState.value
            if (state !is RecoveryRegenState.Confirming || state.isSubmitting) return

            if (!enrolmentRepository.confirmRecoveryRegeneration(typed)) {
                _regenState.value = state.copy(error = "That doesn't match — check the code and try again.")
                return
            }

            _regenState.value = state.copy(isSubmitting = true, error = null)
            viewModelScope.launch {
                enrolmentRepository.finishRecoveryRegeneration().fold(
                    onSuccess = {
                        _regenState.value = RecoveryRegenState.Hidden
                        refresh()
                    },
                    onFailure = {
                        _regenState.value = state.copy(isSubmitting = false, error = "Couldn't save it — check your connection and try again.")
                    },
                )
            }
        }

        fun cancelRecoveryRegeneration() {
            _regenState.value = RecoveryRegenState.Hidden
        }
    }
