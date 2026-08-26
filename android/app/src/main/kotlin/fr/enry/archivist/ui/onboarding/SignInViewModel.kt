package fr.enry.archivist.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.remote.CognitoAuthResult
import fr.enry.archivist.data.remote.PasskeyRegistrationComplete
import fr.enry.archivist.data.remote.PasskeyRegistrationStart
import fr.enry.archivist.data.repo.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SignInError {
    data object InvalidCredentials : SignInError

    data object NetworkError : SignInError

    data class Other(val detail: String?) : SignInError
}

sealed interface SignInUiState {
    data object CheckingExistingSession : SignInUiState

    data class EnterUsername(val error: SignInError? = null, val isSubmitting: Boolean = false) : SignInUiState

    data class EnterPassword(
        val username: String,
        val isFirstSignIn: Boolean = false,
        val error: SignInError? = null,
        val isSubmitting: Boolean = false,
    ) : SignInUiState

    data class SetNewPassword(
        val username: String,
        val session: String,
        val error: SignInError? = null,
        val isSubmitting: Boolean = false,
    ) : SignInUiState

    /** The UI must run the passkey assertion ceremony via [PasskeyCeremony] and call
     * [SignInViewModel.onPasskeyAssertionResult] with what it gets back. */
    data class AwaitingPasskeyAssertion(val username: String, val session: String, val requestOptionsJson: String) :
        SignInUiState

    /** Same idea, for registering a *new* passkey — reachable after any successful
     * password sign-in, since that's the only path that doesn't already prove one
     * exists. [isOptional] just changes the copy the screen shows, not the logic. */
    data class AwaitingPasskeyRegistration(val creationOptionsJson: String, val isOptional: Boolean) : SignInUiState

    data object SignedIn : SignInUiState
}

@HiltViewModel
class SignInViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val instanceStore: InstanceStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.CheckingExistingSession)
        val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val instance = instanceStore.current.first()
                val hasSession = instance != null && authRepository.currentSession(instance.host) != null
                _uiState.value = if (hasSession) SignInUiState.SignedIn else SignInUiState.EnterUsername()
            }
        }

        /** First step for a returning user: try a passkey before ever asking for a
         * password. [CognitoAuthClient] confirmed live that an account with none
         * registered doesn't error here — it falls through to [CognitoAuthResult.NoPasskeyAvailable]. */
        fun continueWithUsername(username: String) {
            val state = _uiState.value
            if (state !is SignInUiState.EnterUsername || state.isSubmitting) return
            _uiState.value = state.copy(isSubmitting = true, error = null)

            viewModelScope.launch {
                when (val result = authRepository.startPasskeySignIn(username)) {
                    is CognitoAuthResult.PasskeyChallenge ->
                        _uiState.value =
                            SignInUiState.AwaitingPasskeyAssertion(username, result.session, result.requestOptionsJson)

                    CognitoAuthResult.NoPasskeyAvailable ->
                        _uiState.value = SignInUiState.EnterPassword(username)

                    else -> _uiState.value = SignInUiState.EnterUsername(error = toError(result))
                }
            }
        }

        fun onPasskeyAssertionResult(credentialJson: Result<String>) {
            val state = _uiState.value
            if (state !is SignInUiState.AwaitingPasskeyAssertion) return

            credentialJson.fold(
                onSuccess = { json ->
                    viewModelScope.launch {
                        val result = authRepository.completePasskeySignIn(state.username, state.session, json)
                        _uiState.value =
                            if (result is CognitoAuthResult.SignedIn) {
                                SignInUiState.SignedIn
                            } else {
                                SignInUiState.EnterUsername(error = toError(result))
                            }
                    }
                },
                onFailure = {
                    // Cancelled, no authenticator, etc. — password is still available.
                    _uiState.value = SignInUiState.EnterPassword(state.username)
                },
            )
        }

        fun signInWithPassword(password: String) {
            val state = _uiState.value
            if (state !is SignInUiState.EnterPassword || state.isSubmitting) return
            _uiState.value = state.copy(isSubmitting = true, error = null)

            viewModelScope.launch {
                when (val result = authRepository.signInWithPassword(state.username, password)) {
                    is CognitoAuthResult.SignedIn -> beginOptionalPasskeyRegistration(isOptional = true)
                    is CognitoAuthResult.NewPasswordRequired ->
                        _uiState.value = SignInUiState.SetNewPassword(state.username, result.session)

                    else -> _uiState.value = state.copy(isSubmitting = false, error = toError(result))
                }
            }
        }

        fun setNewPassword(newPassword: String) {
            val state = _uiState.value
            if (state !is SignInUiState.SetNewPassword || state.isSubmitting) return
            _uiState.value = state.copy(isSubmitting = true, error = null)

            viewModelScope.launch {
                val result = authRepository.completeNewPassword(state.username, newPassword, state.session)
                if (result is CognitoAuthResult.SignedIn) {
                    beginOptionalPasskeyRegistration(isOptional = false)
                } else {
                    _uiState.value = state.copy(isSubmitting = false, error = toError(result))
                }
            }
        }

        fun onPasskeyRegistrationResult(credentialJson: Result<String>) {
            val state = _uiState.value
            if (state !is SignInUiState.AwaitingPasskeyRegistration) return

            credentialJson.fold(
                onSuccess = { json ->
                    viewModelScope.launch {
                        val result = authRepository.completePasskeyRegistration(json)
                        _uiState.value =
                            if (result is PasskeyRegistrationComplete.Success) {
                                SignInUiState.SignedIn
                            } else {
                                // Already have working Cognito tokens either way — a failed
                                // registration isn't a failed sign-in, just a skipped step.
                                SignInUiState.SignedIn
                            }
                    }
                },
                onFailure = { _uiState.value = SignInUiState.SignedIn },
            )
        }

        fun skipPasskeyRegistration() {
            if (_uiState.value is SignInUiState.AwaitingPasskeyRegistration) {
                _uiState.value = SignInUiState.SignedIn
            }
        }

        private suspend fun beginOptionalPasskeyRegistration(isOptional: Boolean) {
            when (val start = authRepository.startPasskeyRegistration()) {
                is PasskeyRegistrationStart.Options ->
                    _uiState.value = SignInUiState.AwaitingPasskeyRegistration(start.creationOptionsJson, isOptional)
                // Already signed in with working tokens — don't block on this.
                else -> _uiState.value = SignInUiState.SignedIn
            }
        }

        private fun toError(result: CognitoAuthResult): SignInError =
            when (result) {
                CognitoAuthResult.InvalidCredentials -> SignInError.InvalidCredentials
                CognitoAuthResult.NetworkError -> SignInError.NetworkError
                is CognitoAuthResult.UnexpectedError -> SignInError.Other(result.message ?: result.type)
                CognitoAuthResult.NoPasskeyAvailable -> SignInError.Other("no passkey registered")
                is CognitoAuthResult.SignedIn -> error("not an error")
                is CognitoAuthResult.NewPasswordRequired -> error("not an error")
                is CognitoAuthResult.PasskeyChallenge -> error("not an error")
            }
    }
