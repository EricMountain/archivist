package fr.enry.archivist.ui.reviewer

import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.R
import fr.enry.archivist.sync.DeviceMediaFile
import fr.enry.archivist.sync.MediaStoreSource
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One tile in the preview grid — either a real on-device file, or one of the bundled
 * samples shown when the device has none. */
sealed interface ReviewerPreviewItem {
    val key: String

    data class Device(val file: DeviceMediaFile) : ReviewerPreviewItem {
        override val key get() = file.contentUri
    }

    data class Sample(
        @param:DrawableRes val drawableRes: Int,
        val label: String,
    ) : ReviewerPreviewItem {
        override val key get() = "sample-$drawableRes"
    }
}

sealed interface ReviewerPreviewUiState {
    data object Loading : ReviewerPreviewUiState

    data class Loaded(
        val items: List<ReviewerPreviewItem>,
        /** True when [items] are the bundled fallback, not the device's own photos —
         * shown to the reviewer as a label, not silently swapped in. */
        val usingSamples: Boolean,
    ) : ReviewerPreviewUiState
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
                    if (files.isEmpty()) {
                        ReviewerPreviewUiState.Loaded(SAMPLE_ITEMS, usingSamples = true)
                    } else {
                        ReviewerPreviewUiState.Loaded(files.map { ReviewerPreviewItem.Device(it) }, usingSamples = false)
                    }
            }
        }

        companion object {
            /** Bundled fallback for a device with nothing on it yet — a bare emulator,
             * most often. Self-authored, tiny, checked in under `res/drawable-nodpi/`. */
            val SAMPLE_ITEMS: List<ReviewerPreviewItem> =
                listOf(
                    R.drawable.reviewer_sample_1 to "Sample photo 1",
                    R.drawable.reviewer_sample_2 to "Sample photo 2",
                    R.drawable.reviewer_sample_3 to "Sample photo 3",
                    R.drawable.reviewer_sample_4 to "Sample photo 4",
                ).map { (res, label) -> ReviewerPreviewItem.Sample(res, label) }
        }
    }
