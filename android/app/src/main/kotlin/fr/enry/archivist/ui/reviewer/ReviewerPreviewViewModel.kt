package fr.enry.archivist.ui.reviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.sync.DeviceMediaFile
import fr.enry.archivist.sync.MediaStoreSource
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReviewerPreviewUiState {
    data object Loading : ReviewerPreviewUiState

    /** No photos or videos anywhere on the device — a bare emulator, most often. Shown
     * as a plain message rather than substituting anything, so a reviewer never mistakes
     * fabricated content for the device's own. */
    data object Empty : ReviewerPreviewUiState

    data class Loaded(val files: List<DeviceMediaFile>) : ReviewerPreviewUiState
}

/**
 * Plan step 2.17. Deliberately depends on [MediaStoreSource] alone — the same seam
 * [fr.enry.archivist.sync.Scanner] uses to read the device's own photos — and nothing
 * else. That is the actual guarantee this screen makes, not just its UI: there is no
 * `AuthRepository`, `ArchivistApiFactory`, `CognitoAuthClient` or `UploadRepository`
 * anywhere in this class's constructor, so nothing reachable from here can make a
 * network call. `ReviewerPreviewNoNetworkTest` asserts this by reflection so it can't
 * regress silently.
 *
 * Deliberately does not reuse `TimelineViewModel`/`PhotoDetailRepository`/Room/Paging 3/
 * `EncryptedImageFetcher` — those solve problems (server pagination, ciphertext
 * decryption) that don't exist for the device's own plaintext files, and reusing them
 * would be the easiest way to accidentally drag a network dependency back into this
 * screen.
 */
@HiltViewModel
class ReviewerPreviewViewModel
    @Inject
    constructor(
        private val mediaStoreSource: MediaStoreSource,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ReviewerPreviewUiState>(ReviewerPreviewUiState.Loading)
        val uiState: StateFlow<ReviewerPreviewUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val files =
                    mediaStoreSource.listFolders()
                        .flatMap { folder -> mediaStoreSource.listFiles(folder.bucketId) }
                        .sortedByDescending { it.dateModified }

                _uiState.value =
                    if (files.isEmpty()) ReviewerPreviewUiState.Empty else ReviewerPreviewUiState.Loaded(files)
            }
        }
    }
