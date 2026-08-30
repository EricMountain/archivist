package fr.enry.archivist.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.db.FolderSelectionDao
import fr.enry.archivist.data.local.db.FolderSelectionEntity
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.sync.MediaStoreSource
import fr.enry.archivist.sync.Scanner
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One MediaStore folder as the settings screen shows it — [MediaStoreSource]'s view of
 * what exists, joined with [FolderSelectionDao]'s view of what's selected. */
data class FolderUiItem(
    val bucketId: String,
    val displayName: String,
    val itemCount: Int,
    val enabled: Boolean,
)

sealed interface FoldersUiState {
    /** The runtime permission hasn't been granted (or hasn't been checked) yet — the
     * screen shows the rationale, not a folder list, until [FoldersViewModel.onPermissionGranted]. */
    data object NeedsPermission : FoldersUiState

    data object Loading : FoldersUiState

    data class Loaded(
        val folders: List<FolderUiItem>,
        val isScanning: Boolean = false,
        val lastScanQueued: Int? = null,
        val error: String? = null,
    ) : FoldersUiState
}

/**
 * Plan step 2.7. Owns folder selection (via [FolderSelectionDao]) and triggers
 * [Scanner] once a folder is turned on — "selecting a folder queues its unsynced
 * files" (the step's own "Done when") reads as an immediate consequence of selection,
 * not something waiting on a separate manual action.
 */
@HiltViewModel
class FoldersViewModel
    @Inject
    constructor(
        private val mediaStoreSource: MediaStoreSource,
        private val folderSelectionDao: FolderSelectionDao,
        private val scanner: Scanner,
        private val enrolmentRepository: EnrolmentRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<FoldersUiState>(FoldersUiState.NeedsPermission)
        val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()

        /** Call once the runtime permission is confirmed granted — whether that's
         * because it already was (checked at screen launch) or because the user just
         * granted it. Reloads the folder list either way. */
        fun onPermissionGranted() {
            viewModelScope.launch {
                loadFolders()
            }
        }

        private suspend fun loadFolders() {
            _uiState.value = FoldersUiState.Loading
            val deviceFolders = mediaStoreSource.listFolders()
            val selections = folderSelectionDao.observeAll().first().associateBy { it.folderUri }
            _uiState.value =
                FoldersUiState.Loaded(
                    folders =
                        deviceFolders.map { folder ->
                            FolderUiItem(
                                bucketId = folder.bucketId,
                                displayName = folder.displayName,
                                itemCount = folder.itemCount,
                                enabled = selections[folder.bucketId]?.enabled ?: false,
                            )
                        },
                )
        }

        fun setFolderEnabled(
            folder: FolderUiItem,
            enabled: Boolean,
        ) {
            val state = _uiState.value
            if (state !is FoldersUiState.Loaded) return

            viewModelScope.launch {
                val existing = folderSelectionDao.getByFolderUri(folder.bucketId)
                folderSelectionDao.upsert(
                    FolderSelectionEntity(
                        folderUri = folder.bucketId,
                        displayName = folder.displayName,
                        enabled = enabled,
                        // Preserve the original selection time through a later toggle
                        // rather than bumping it every time.
                        addedAt = existing?.addedAt ?: nowIso(),
                    ),
                )
                _uiState.value =
                    state.copy(
                        folders = state.folders.map { if (it.bucketId == folder.bucketId) it.copy(enabled = enabled) else it },
                    )
                if (enabled) scan()
            }
        }

        private suspend fun scan() {
            val state = _uiState.value
            if (state !is FoldersUiState.Loaded || state.isScanning) return
            _uiState.value = state.copy(isScanning = true, error = null)

            val hashSecretReady = enrolmentRepository.ensureHashSecret()
            if (hashSecretReady.isFailure) {
                _uiState.value =
                    (_uiState.value as? FoldersUiState.Loaded)?.copy(
                        isScanning = false,
                        error = "Couldn't prepare to scan — check your connection and try again.",
                    ) ?: return
                return
            }

            val result = scanner.scan()
            _uiState.value =
                (_uiState.value as? FoldersUiState.Loaded)?.copy(
                    isScanning = false,
                    lastScanQueued = result.getOrNull(),
                    error = if (result.isFailure) "Scan failed — try again." else null,
                ) ?: return
        }
    }

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
