package fr.enry.archivist.ui.detail

import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoEntity
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
    }
