package fr.enry.archivist.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.remote.SUPPORTED_CRYPTO_VERSION
import fr.enry.archivist.data.repo.ConnectOutcome
import fr.enry.archivist.data.repo.InstanceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ConnectUiState {
    /** Reading DataStore to see whether an instance is already connected. */
    data object CheckingStoredInstance : ConnectUiState

    data class Connected(val instanceName: String) : ConnectUiState

    data class NeedsConnection(
        val isConnecting: Boolean = false,
        val error: ConnectError? = null,
        /** Set when this state was reached via [ConnectViewModel.changeInstance] —
         * the host the user is backing out of, offered as a starting point rather
         * than an empty field. */
        val prefillHost: String? = null,
    ) : ConnectUiState
}

sealed interface ConnectError {
    data object InvalidHost : ConnectError

    data object HostNotFound : ConnectError

    data object NotArchivist : ConnectError

    data class ServerTooNew(val serverVersion: Int, val supportedVersion: Int) : ConnectError
}

@HiltViewModel
class ConnectViewModel
    @Inject
    constructor(
        private val instanceRepository: InstanceRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.CheckingStoredInstance)
        val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val stored = instanceRepository.currentInstance.first()
                _uiState.value =
                    if (stored != null) {
                        ConnectUiState.Connected(stored.document.instanceName)
                    } else {
                        ConnectUiState.NeedsConnection()
                    }
            }
        }

        fun connect(hostInput: String) {
            val state = _uiState.value
            if (state !is ConnectUiState.NeedsConnection || state.isConnecting) return
            _uiState.value = state.copy(isConnecting = true, error = null)

            viewModelScope.launch {
                _uiState.value =
                    when (val outcome = instanceRepository.connect(hostInput)) {
                        is ConnectOutcome.Connected -> ConnectUiState.Connected(outcome.instanceName)
                        ConnectOutcome.InvalidHost -> ConnectUiState.NeedsConnection(error = ConnectError.InvalidHost)
                        ConnectOutcome.HostNotFound -> ConnectUiState.NeedsConnection(error = ConnectError.HostNotFound)
                        ConnectOutcome.NotArchivist -> ConnectUiState.NeedsConnection(error = ConnectError.NotArchivist)
                        is ConnectOutcome.ServerTooNew ->
                            ConnectUiState.NeedsConnection(
                                error = ConnectError.ServerTooNew(outcome.serverVersion, SUPPORTED_CRYPTO_VERSION),
                            )
                    }
            }
        }

        /** Back out of a connected instance to pick a different one — the stored
         * instance (and any session against it) is left untouched until [connect]
         * actually succeeds against a new host, so this is non-destructive. */
        fun changeInstance() {
            val state = _uiState.value
            if (state !is ConnectUiState.Connected) return

            viewModelScope.launch {
                val host = instanceRepository.currentInstance.first()?.host
                _uiState.value = ConnectUiState.NeedsConnection(prefillHost = host)
            }
        }
    }
