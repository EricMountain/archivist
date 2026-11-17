package fr.enry.archivist.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.data.repo.PlaceRepository
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Whether the vocabulary is usable yet. [FAILED] only when the sync failed *and* nothing
 * is cached; with a cache, search runs on it silently (offline included). */
enum class VocabularyStatus { LOADING, READY, FAILED }

/**
 * Geolocation plan step 8's search screen state. Like every ViewModel in this app it is
 * effectively Activity-scoped (no navigation library; see `android/AGENTS.md`), so
 * [onOpened] resets it each time the screen is entered from the timeline menu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val placeRepository: PlaceRepository,
        instanceStore: InstanceStore,
    ) : ViewModel() {
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _selected = MutableStateFlow<PlaceSuggestion?>(null)

        /** The place whose photos are showing, or null while choosing one. */
        val selected: StateFlow<PlaceSuggestion?> = _selected.asStateFlow()

        private val syncFailed = MutableStateFlow(false)
        private val syncing = MutableStateFlow(false)

        /** Null until Room has answered once; then the cached vocabulary, if any. The
         * wrapper keeps "not read yet" apart from "read, nothing cached". */
        private val cached: StateFlow<CachedVocabulary?> =
            placeRepository.vocabulary().map { CachedVocabulary(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val status: StateFlow<VocabularyStatus> =
            combine(cached, syncing, syncFailed) { row, inFlight, failed ->
                when {
                    row?.vocabulary != null -> VocabularyStatus.READY
                    row == null || inFlight -> VocabularyStatus.LOADING
                    failed -> VocabularyStatus.FAILED
                    // Synced, still nothing: no instance, or no body. Nothing to search.
                    else -> VocabularyStatus.READY
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabularyStatus.LOADING)

        /** Whether the owner has any located photo at all, for the empty state. */
        val hasPlaces: StateFlow<Boolean> =
            cached.map { row -> row?.vocabulary?.let { it.countries.isNotEmpty() } ?: false }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** The device locale is read per query, so a locale change while the app runs
         * shows country names in the new language on the next keystroke. */
        val suggestions: StateFlow<List<PlaceSuggestion>> =
            combine(cached, _query) { row, q ->
                row?.vocabulary?.let { searchPlaces(it, q, Locale.getDefault()) } ?: emptyList()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val results: Flow<PagingData<PhotoEntity>> =
            _selected
                .flatMapLatest { s ->
                    if (s == null) flowOf(PagingData.empty()) else placeRepository.facetPhotos(s.level.facetType, s.facetValue)
                }.cachedIn(viewModelScope)

        val cdnHost: StateFlow<String?> =
            instanceStore.current
                .map { it?.host }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** A fresh search each time the screen opens, and a revalidation of the
         * vocabulary (usually a 304). */
        fun onOpened() {
            _query.value = ""
            _selected.value = null
            refresh()
        }

        fun refresh() {
            if (syncing.value) return
            syncing.value = true
            syncFailed.value = false
            viewModelScope.launch {
                try {
                    placeRepository.syncVocabulary()
                } catch (e: IOException) {
                    syncFailed.value = true
                } catch (e: HttpException) {
                    syncFailed.value = true
                } finally {
                    syncing.value = false
                }
            }
        }

        fun onQueryChange(q: String) {
            _query.value = q
        }

        fun select(suggestion: PlaceSuggestion) {
            _selected.value = suggestion
        }

        fun clearSelection() {
            _selected.value = null
        }
    }

private data class CachedVocabulary(val vocabulary: PlacesResponse?)
