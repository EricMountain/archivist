package fr.enry.archivist.ui.detail

import android.content.IntentSender
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.DeleteMode
import fr.enry.archivist.data.repo.DeleteOutcome
import fr.enry.archivist.data.repo.DeleteRepository
import fr.enry.archivist.data.repo.PhotoDetail
import fr.enry.archivist.data.repo.PhotoDetailRepository
import fr.enry.archivist.data.repo.PhotoRepository
import fr.enry.archivist.data.repo.RenditionSummary
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import retrofit2.HttpException

/** Plan step 2.13's delete flow, keyed by nothing — only one delete is ever in flight
 * at a time (the dialog is modal), unlike [PhotoDetailUiState]/[OriginalUiState] which
 * are per-photo/per-rendition. */
sealed interface DeleteUiState {
    data object Idle : DeleteUiState

    data object InProgress : DeleteUiState

    /** The caller must launch [intentSender] via
     * `ActivityResultContracts.StartIntentSenderForResult` and report the result back
     * via [DetailViewModel.finishMediaDelete] — see [DeleteOutcome.NeedsMediaConfirmation]'s
     * own doc for why approving it is itself the deletion. */
    data class NeedsMediaConfirmation(val intentSender: IntentSender, val photoId: String) : DeleteUiState

    data object Done : DeleteUiState

    data class Error(val message: String) : DeleteUiState
}

/** One photo's detail fetch/decrypt, keyed by photoId in [DetailViewModel.details] —
 * a page the pager has already scrolled past keeps whatever it last had rather than
 * reverting to [Loading] on every swipe back. */
sealed interface PhotoDetailUiState {
    data object Loading : PhotoDetailUiState

    data class Loaded(val detail: PhotoDetail) : PhotoDetailUiState

    data class Error(val message: String) : PhotoDetailUiState
}

/** What tapping "view original" on one rendition produced, keyed by renditionId in
 * [DetailViewModel.originals] — see that field's own doc for why per-rendition rather
 * than per-photo. */
sealed interface OriginalUiState {
    data object Loading : OriginalUiState

    data class Ready(val bytes: ByteArray) : OriginalUiState

    data class Error(val message: String) : OriginalUiState
}

/**
 * Plan step 2.12. [photos] is the swipe order the detail pager works with —
 * [PhotoRepository.observeTimeline]'s plain list, not the grid's `Paging` flow, see
 * that function's own doc. [details] is fetched lazily, one photo at a time
 * ([ensureDetail]), as the pager settles on each page — fetching every photo's detail
 * up front would mean one `GET /photos/{id}` call per photo before the user ever swipes
 * to most of them.
 */
@HiltViewModel
class DetailViewModel
    @Inject
    constructor(
        photoRepository: PhotoRepository,
        private val photoDetailRepository: PhotoDetailRepository,
        private val deleteRepository: DeleteRepository,
        instanceStore: InstanceStore,
    ) : ViewModel() {
        val photos: StateFlow<List<PhotoEntity>> =
            photoRepository.observeTimeline()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** The CDN host the 2048 thumbnail is fetched from — same source as
         * `TimelineViewModel.cdnHost`. */
        val cdnHost: StateFlow<String?> =
            instanceStore.current
                .map { it?.host }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private val _details = MutableStateFlow<Map<String, PhotoDetailUiState>>(emptyMap())
        val details: StateFlow<Map<String, PhotoDetailUiState>> = _details.asStateFlow()

        /** Keyed by renditionId, not photoId: an asset can have more than one rendition
         * (JPEG + RAW), and "view original" is a per-rendition action. */
        private val _originals = MutableStateFlow<Map<String, OriginalUiState>>(emptyMap())
        val originals: StateFlow<Map<String, OriginalUiState>> = _originals.asStateFlow()

        /** No-op if already loading or loaded — the pager calls this on every settle,
         * including swiping back to a photo it's already fetched. */
        fun ensureDetail(photoId: String) {
            if (_details.value.containsKey(photoId)) return
            _details.update { it + (photoId to PhotoDetailUiState.Loading) }
            viewModelScope.launch {
                val state =
                    try {
                        PhotoDetailUiState.Loaded(photoDetailRepository.fetchDetail(photoId))
                    } catch (e: IOException) {
                        PhotoDetailUiState.Error(e.message ?: "network error")
                    } catch (e: HttpException) {
                        PhotoDetailUiState.Error("HTTP ${e.code()}")
                    }
                _details.update { it + (photoId to state) }
            }
        }

        fun viewOriginal(
            photoId: String,
            encDek: String,
            rendition: RenditionSummary,
        ) {
            if (_originals.value[rendition.renditionId] is OriginalUiState.Ready) return
            _originals.update { it + (rendition.renditionId to OriginalUiState.Loading) }
            viewModelScope.launch {
                val state =
                    try {
                        OriginalUiState.Ready(photoDetailRepository.downloadOriginal(photoId, encDek, rendition))
                    } catch (e: IOException) {
                        OriginalUiState.Error(e.message ?: "couldn't download the original")
                    } catch (e: HttpException) {
                        OriginalUiState.Error("HTTP ${e.code()}")
                    }
                _originals.update { it + (rendition.renditionId to state) }
            }
        }

        fun dismissOriginal(renditionId: String) {
            _originals.update { it - renditionId }
        }

        private val _deleteState = MutableStateFlow<DeleteUiState>(DeleteUiState.Idle)
        val deleteState: StateFlow<DeleteUiState> = _deleteState.asStateFlow()

        fun deletePhoto(
            photoId: String,
            mode: DeleteMode,
        ) {
            _deleteState.value = DeleteUiState.InProgress
            viewModelScope.launch {
                _deleteState.value =
                    when (val outcome = deleteRepository.delete(photoId, mode)) {
                        DeleteOutcome.Done -> DeleteUiState.Done
                        is DeleteOutcome.NeedsMediaConfirmation ->
                            DeleteUiState.NeedsMediaConfirmation(outcome.intentSender, outcome.photoId)
                        is DeleteOutcome.Error -> DeleteUiState.Error(outcome.message)
                    }
            }
        }

        /** Called once the caller's `StartIntentSenderForResult` launch for a
         * [DeleteUiState.NeedsMediaConfirmation] returns. Both outcomes land on
         * [DeleteUiState.Done]: [approved] performs the `upload_queue` cleanup (the file
         * is genuinely gone — approving the system dialog is itself the deletion, see
         * [DeleteRepository]'s own doc); a cancel leaves that row alone, since the local
         * file still exists and the row is still an accurate record of it — the already-
         * written tombstone is what prevents a re-upload either way, this is just about
         * not discarding a still-accurate `upload_queue` row for nothing. */
        fun finishMediaDelete(
            photoId: String,
            approved: Boolean,
        ) {
            viewModelScope.launch {
                if (approved) deleteRepository.finishMediaDelete(photoId)
                _deleteState.value = DeleteUiState.Done
            }
        }

        fun dismissDelete() {
            _deleteState.value = DeleteUiState.Idle
        }
    }
