package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.repo.AccountRepository
import fr.enry.archivist.data.repo.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountUiState(
    val isWorking: Boolean = false,
    val error: String? = null,
    /** Either action ends the session the same way — [MainActivity][fr.enry.archivist.MainActivity]'s
     * caller reacts to this exactly once and drops back to the sign-in screen. */
    val sessionEnded: Boolean = false,
)

/** Plan step 2.14's Settings > Account section — "sign out, delete account (with
 * confirmation)". [AuthRepository.signOut] already existed (plan step 2.4); the new
 * work here is [AccountRepository.deleteAccount] and the confirmation gate in
 * [AccountScreen]. */
@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AccountUiState())
        val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

        fun signOut() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isWorking = true)
                authRepository.signOut()
                _uiState.value = AccountUiState(sessionEnded = true)
            }
        }

        fun deleteAccount() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isWorking = true, error = null)
                accountRepository.deleteAccount().fold(
                    onSuccess = { _uiState.value = AccountUiState(sessionEnded = true) },
                    onFailure = {
                        _uiState.value = AccountUiState(error = "Couldn't delete your account — check your connection and try again.")
                    },
                )
            }
        }

        /** This app has no navigation library — `hiltViewModel()` in a back-stack-less
         * composable resolves to the Activity's own `ViewModelStore` (see
         * `android/AGENTS.md`'s note on `DetailViewModel`), so this same instance is
         * reused the next time Settings > Account is opened, including after a later
         * sign-in. Without this, `sessionEnded` staying `true` would fire
         * [AccountScreen]'s `LaunchedEffect` immediately on that next visit, bouncing
         * the just-signed-in user straight back out. Called from that same effect,
         * right after acting on `sessionEnded` once — not on the next explicit action. */
        fun acknowledgeSessionEnded() {
            _uiState.value = AccountUiState()
        }
    }
