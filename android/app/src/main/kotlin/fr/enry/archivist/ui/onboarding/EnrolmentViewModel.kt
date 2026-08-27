package fr.enry.archivist.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.crypto.DeviceKeystoreUnsupportedException
import fr.enry.archivist.crypto.KeyCustody
import fr.enry.archivist.crypto.RecoveryCode
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.data.repo.EnrolmentStep
import fr.enry.archivist.data.repo.RecoveryAttemptResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EnrolmentUiState {
    data object Checking : EnrolmentUiState

    /** [reenrolling] is true only when this device's *own* previous wrapping was just
     * found permanently invalidated (a lock-screen change) — changes the copy the
     * screen shows, not the logic. */
    data class EnterRecoveryCode(
        val reenrolling: Boolean,
        val error: String? = null,
        val isSubmitting: Boolean = false,
    ) : EnrolmentUiState

    /** First device only. [formattedCode] is `XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`; nothing
     * has been sent to the server yet. */
    data class ShowRecoveryCode(val formattedCode: String) : EnrolmentUiState

    data class ConfirmRecoveryCode(
        val formattedCode: String,
        val error: String? = null,
        val isSubmitting: Boolean = false,
    ) : EnrolmentUiState

    data class DeviceKeystoreUnsupported(val sdkInt: Int) : EnrolmentUiState

    /** The UI must offer to run `KeyguardManager.createConfirmDeviceCredentialIntent`
     * and call [EnrolmentViewModel.checkStep] again on success. */
    data object NeedsDeviceUnlock : EnrolmentUiState

    data object NetworkError : EnrolmentUiState

    data class Failed(val message: String) : EnrolmentUiState

    data object Unlocked : EnrolmentUiState
}

@HiltViewModel
class EnrolmentViewModel
    @Inject
    constructor(
        private val repository: EnrolmentRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<EnrolmentUiState>(EnrolmentUiState.Checking)
        val uiState: StateFlow<EnrolmentUiState> = _uiState.asStateFlow()

        init {
            checkStep()
        }

        fun checkStep() {
            _uiState.value = EnrolmentUiState.Checking
            viewModelScope.launch {
                when (val step = repository.determineStep()) {
                    EnrolmentStep.Unlocked -> _uiState.value = EnrolmentUiState.Unlocked
                    EnrolmentStep.NeedsFirstEnrolment -> beginFirstEnrolment()
                    is EnrolmentStep.NeedsRecoveryCode ->
                        _uiState.value = EnrolmentUiState.EnterRecoveryCode(reenrolling = step.reenrolling)
                    EnrolmentStep.NeedsDeviceUnlock -> _uiState.value = EnrolmentUiState.NeedsDeviceUnlock
                    EnrolmentStep.NetworkError -> _uiState.value = EnrolmentUiState.NetworkError
                    is EnrolmentStep.Failed -> _uiState.value = EnrolmentUiState.Failed(step.message)
                }
            }
        }

        private suspend fun beginFirstEnrolment() {
            repository.beginFirstEnrolment().fold(
                onSuccess = { enrolment ->
                    _uiState.value = EnrolmentUiState.ShowRecoveryCode(RecoveryCode.format(enrolment.recoveryCode.code))
                },
                onFailure = { error ->
                    _uiState.value =
                        when (error) {
                            is DeviceKeystoreUnsupportedException -> EnrolmentUiState.DeviceKeystoreUnsupported(error.sdkInt)
                            else -> EnrolmentUiState.Failed(error.message ?: "couldn't generate a device key")
                        }
                },
            )
        }

        /** The user has seen the code and says they've saved it — move to the "type it
         * back" gate rather than trusting a checkbox. */
        fun proceedToConfirmation() {
            val state = _uiState.value
            if (state is EnrolmentUiState.ShowRecoveryCode) {
                _uiState.value = EnrolmentUiState.ConfirmRecoveryCode(formattedCode = state.formattedCode)
            }
        }

        fun showCodeAgain() {
            val state = _uiState.value
            if (state is EnrolmentUiState.ConfirmRecoveryCode) {
                _uiState.value = EnrolmentUiState.ShowRecoveryCode(state.formattedCode)
            }
        }

        fun confirmTypedCode(typed: String) {
            val state = _uiState.value
            if (state !is EnrolmentUiState.ConfirmRecoveryCode || state.isSubmitting) return

            if (!repository.confirmFirstEnrolment(typed)) {
                _uiState.value = state.copy(error = "That doesn't match — check the code and try again.")
                return
            }

            _uiState.value = state.copy(isSubmitting = true, error = null)
            viewModelScope.launch {
                repository.finishFirstEnrolment().fold(
                    onSuccess = { _uiState.value = EnrolmentUiState.Unlocked },
                    onFailure = {
                        _uiState.value =
                            state.copy(
                                isSubmitting = false,
                                error = "Couldn't save your enrolment — check your connection and try again.",
                            )
                    },
                )
            }
        }

        /** The later-device / re-enrolment path. */
        fun submitRecoveryCode(typed: String) {
            val state = _uiState.value
            if (state !is EnrolmentUiState.EnterRecoveryCode || state.isSubmitting) return
            _uiState.value = state.copy(isSubmitting = true, error = null)

            viewModelScope.launch {
                when (val result = repository.attemptRecovery(typed)) {
                    RecoveryAttemptResult.Success -> _uiState.value = EnrolmentUiState.Unlocked
                    RecoveryAttemptResult.Mistyped ->
                        _uiState.value = state.copy(isSubmitting = false, error = "That's mistyped — check the code.")
                    RecoveryAttemptResult.WrongCodeOrCorrupted ->
                        _uiState.value =
                            state.copy(isSubmitting = false, error = "That's not the right code for this library.")
                    is RecoveryAttemptResult.DeviceKeystoreUnsupported ->
                        _uiState.value = EnrolmentUiState.DeviceKeystoreUnsupported(result.sdkInt)
                    RecoveryAttemptResult.NetworkError ->
                        _uiState.value = state.copy(isSubmitting = false, error = "Couldn't reach the server.")
                    is RecoveryAttemptResult.Failed ->
                        _uiState.value = state.copy(isSubmitting = false, error = result.message)
                }
            }
        }
    }
