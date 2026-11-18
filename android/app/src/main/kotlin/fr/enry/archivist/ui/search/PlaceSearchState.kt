package fr.enry.archivist.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.data.repo.PlaceRepository
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Timeline place filter step 4: the `⋮` menu's three states and what each input does in
 * them (`docs/plans/timeline-place-filter.md`, "Interaction design": "States" and "Back and
 * dismissing"). A pure reducer, so the whole table is unit-tested without Compose, the same
 * convention as `timelineContentState` and `TimelineScale`.
 */
enum class OverlayMode {
    /** A: the `⋮` button, and the filter chip when a filter is active. */
    COLLAPSED,

    /** B: the card with the "Search places" field (not focused) and the menu entries. */
    EXPANDED,

    /** C: the field focused, the menu entries faded out, the suggestion panel below. */
    SEARCHING,
}

/** What the user did. */
sealed interface OverlayEvent {
    /** Tapped `⋮`. */
    data object MenuButton : OverlayEvent

    /** Tapped the "Search places" field in B. */
    data object FieldTapped : OverlayEvent

    /** Tapped the chip's body (not its ✕) in A. */
    data object ChipBody : OverlayEvent

    /** Tapped the chip's ✕. */
    data object ChipClear : OverlayEvent

    /** Tapped outside the card in B. */
    data object OutsideTap : OverlayEvent

    /** Tapped the grid below the suggestion panel in C. */
    data object GridTap : OverlayEvent

    /** The keyboard's Search action in C. */
    data object ImeSearch : OverlayEvent

    /** Picked "Settings" in B. */
    data object SettingsPicked : OverlayEvent

    /** System Back. [keyboardOpen]: the IME is showing (it takes that Back itself). */
    data class Back(val keyboardOpen: Boolean) : OverlayEvent
}

/** Side effects of a transition, for the screen to carry out. */
enum class OverlayEffect {
    /** The selection becomes the active filter now, skipping the debounce (F6). */
    APPLY_NOW,

    /** No filter, the stream cache emptied, the overlay's selection reset (F8, F11). */
    CLEAR_FILTER,

    /** Revalidate the place vocabulary (entering C). */
    REVALIDATE,

    /** Focus the field and open the keyboard. */
    FOCUS_FIELD,

    /** Drop focus and close the keyboard. */
    HIDE_KEYBOARD,

    /** Open the Settings screen. */
    OPEN_SETTINGS,
}

/**
 * The outcome of one [OverlayEvent]. [consumed]: the input stops here (an outside tap in B
 * doesn't reach the grid, a tap on the grid in C doesn't open a photo, a Back doesn't leave
 * the app). False leaves it to whoever is next: the system for Back, the grid for a tap.
 */
data class OverlayTransition(
    val mode: OverlayMode,
    val effects: Set<OverlayEffect> = emptySet(),
    val consumed: Boolean = true,
)

/**
 * The menu's state machine. [filterActive]: a place filter is applied (including one with
 * no places).
 *
 * | Where | Back | Other |
 * | --- | --- | --- |
 * | C, keyboard open | the system's (closes the keyboard) | IME Search: apply now, to A |
 * | C, keyboard closed | to A, keeping the filter | a grid tap: to A, consumed |
 * | B | to A | an outside tap: to A, consumed |
 * | A, filter active | clears the filter | ✕: clears; the chip's body: to C |
 * | A, no filter | not handled: leaves the app | |
 */
fun reduceOverlay(
    mode: OverlayMode,
    filterActive: Boolean,
    event: OverlayEvent,
): OverlayTransition {
    val unchanged = OverlayTransition(mode, consumed = false)
    return when (event) {
        OverlayEvent.MenuButton ->
            when (mode) {
                OverlayMode.COLLAPSED -> OverlayTransition(OverlayMode.EXPANDED)
                OverlayMode.EXPANDED -> OverlayTransition(OverlayMode.COLLAPSED)
                OverlayMode.SEARCHING -> unchanged
            }

        OverlayEvent.FieldTapped ->
            if (mode == OverlayMode.EXPANDED) {
                OverlayTransition(OverlayMode.SEARCHING, setOf(OverlayEffect.FOCUS_FIELD, OverlayEffect.REVALIDATE))
            } else {
                unchanged
            }

        OverlayEvent.ChipBody ->
            if (mode == OverlayMode.COLLAPSED && filterActive) {
                OverlayTransition(OverlayMode.SEARCHING, setOf(OverlayEffect.FOCUS_FIELD, OverlayEffect.REVALIDATE))
            } else {
                unchanged
            }

        OverlayEvent.ChipClear ->
            if (filterActive) OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.CLEAR_FILTER)) else unchanged

        OverlayEvent.OutsideTap ->
            if (mode == OverlayMode.EXPANDED) OverlayTransition(OverlayMode.COLLAPSED) else unchanged

        OverlayEvent.GridTap ->
            if (mode == OverlayMode.SEARCHING) {
                OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.HIDE_KEYBOARD))
            } else {
                unchanged
            }

        OverlayEvent.ImeSearch ->
            if (mode == OverlayMode.SEARCHING) {
                OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.APPLY_NOW, OverlayEffect.HIDE_KEYBOARD))
            } else {
                unchanged
            }

        OverlayEvent.SettingsPicked ->
            if (mode == OverlayMode.EXPANDED) {
                OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.OPEN_SETTINGS))
            } else {
                unchanged
            }

        is OverlayEvent.Back ->
            when {
                mode == OverlayMode.SEARCHING && event.keyboardOpen -> unchanged
                mode == OverlayMode.SEARCHING -> OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.HIDE_KEYBOARD))
                mode == OverlayMode.EXPANDED -> OverlayTransition(OverlayMode.COLLAPSED)
                filterActive -> OverlayTransition(OverlayMode.COLLAPSED, setOf(OverlayEffect.CLEAR_FILTER))
                else -> unchanged
            }
    }
}

/** Whether the screen should intercept Back at all in [mode]: everywhere but A with no
 * filter, where Back leaves the app as it always has. */
fun overlayHandlesBack(
    mode: OverlayMode,
    filterActive: Boolean,
): Boolean = mode != OverlayMode.COLLAPSED || filterActive

/** Whether the vocabulary is usable yet. [FAILED] only when the sync failed *and* nothing
 * is cached; with a cache, search runs on it silently (offline included). */
enum class VocabularyStatus { LOADING, READY, FAILED }

/**
 * The suggestion overlay's state (plan step 4; takes over the old search screen's vocabulary
 * status): the place vocabulary, its status, and the [selection] the overlay draws.
 *
 * The selection lives here for as long as the filter does, so reopening the overlay from
 * the chip, or from the field in B, shows the query and ticks that are applied. [reset]
 * (on clearing the filter) starts over. Like every ViewModel in this app it is effectively
 * Activity-scoped (no navigation library; `android/AGENTS.md`).
 *
 * The overlay forwards every change to `TimelineViewModel.onSelectionChanged`, which owns
 * the debounce and the active filter; nothing here applies anything.
 */
@HiltViewModel
class PlaceSearchViewModel
    @Inject
    constructor(
        private val placeRepository: PlaceRepository,
    ) : ViewModel() {
        private val syncFailed = MutableStateFlow(false)
        private val syncing = MutableStateFlow(false)

        /** Null until Room has answered once; then the cached vocabulary, if any. The
         * wrapper keeps "not read yet" apart from "read, nothing cached". */
        private val cached: StateFlow<OverlayVocabulary?> =
            placeRepository.vocabulary().map { OverlayVocabulary(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val status: StateFlow<VocabularyStatus> =
            combine(cached, syncing, syncFailed) { row, inFlight, failed ->
                vocabularyStatus(loaded = row != null, cached = row?.vocabulary != null, syncing = inFlight, failed = failed)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, VocabularyStatus.LOADING)

        /** Whether the owner has any located photo at all, for the empty state. */
        val hasPlaces: StateFlow<Boolean> =
            cached.map { row -> row?.vocabulary?.countries?.isNotEmpty() ?: false }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val _selection = MutableStateFlow(SelectionState.initial("", SelectionTree.EMPTY))

        /** What the overlay shows ticked, over the current query's matches. */
        val selection: StateFlow<SelectionState> = _selection.asStateFlow()

        private fun vocabulary(): PlacesResponse? = cached.value?.vocabulary

        private fun treeFor(query: String): SelectionTree =
            vocabulary()?.let { selectionTreeFor(it, query, Locale.getDefault()) } ?: SelectionTree.EMPTY

        init {
            // A vocabulary that arrives (or is revalidated) re-matches the current query;
            // ticks and exclusions carry over by key.
            cached
                .onEach { row ->
                    if (row != null) {
                        _selection.value = _selection.value.withQuery(_selection.value.query, treeFor(_selection.value.query)).keepingTicksOf(_selection.value)
                    }
                }.launchIn(viewModelScope)
        }

        fun onQueryChange(query: String) {
            _selection.value = _selection.value.withQuery(query, treeFor(query))
        }

        fun toggle(key: String) {
            _selection.value = _selection.value.toggle(key)
        }

        fun setAll(
            groupKey: String,
            ticked: Boolean,
        ) {
            _selection.value = _selection.value.setAll(groupKey, ticked)
        }

        /** The filter was cleared: an empty query, nothing ticked. */
        fun reset() {
            _selection.value = SelectionState.initial("", treeFor(""))
        }

        /** Entering C: revalidate the vocabulary (usually a 304). */
        fun revalidate() {
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
    }

/** [PlaceSearchViewModel.status]'s rule: [loaded] Room has answered, [cached] there is a
 * vocabulary row, [syncing] a revalidation is in flight, [failed] the last one failed. */
internal fun vocabularyStatus(
    loaded: Boolean,
    cached: Boolean,
    syncing: Boolean,
    failed: Boolean,
): VocabularyStatus =
    when {
        cached -> VocabularyStatus.READY
        !loaded || syncing -> VocabularyStatus.LOADING
        failed -> VocabularyStatus.FAILED
        // Synced, still nothing: no instance, or no body. Nothing to search.
        else -> VocabularyStatus.READY
    }

/** A re-match of the same query keeps [previous]'s quick picks and its "last change was a
 * tick" flag: only the tree changed, not anything the user did. */
private fun SelectionState.keepingTicksOf(previous: SelectionState): SelectionState = copy(quickPicks = previous.quickPicks, touched = previous.touched)

private data class OverlayVocabulary(val vocabulary: PlacesResponse?)
