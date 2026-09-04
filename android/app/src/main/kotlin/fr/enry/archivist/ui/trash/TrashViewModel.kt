package fr.enry.archivist.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.ThumbEntry
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.TrashEntryDto
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** One `GET /trash` entry, trimmed to what [TrashScreen] shows — same shape as
 * [fr.enry.archivist.ui.timeline.TimelineItem.Photo], minus the fields the trash list
 * doesn't need, plus the blocked-re-upload warning fields. */
data class TrashItem(
    val photoId: String,
    val takenAt: String,
    val tzOffsetMin: Int,
    val thumbs: Map<Int, ThumbEntry>,
    val encDek: String,
    val blockedAttempts: Int?,
    val lastAttemptAt: String?,
    val lastAttemptBy: String?,
)

sealed interface TrashUiState {
    data object Loading : TrashUiState

    data class Loaded(val items: List<TrashItem>) : TrashUiState

    data class Error(val message: String) : TrashUiState
}

/**
 * Plan step 2.13's "surface blocked re-uploads in the trash list" — see
 * `design.md`'s "Tombstones expire, and blocked attempts are surfaced". Deliberately not
 * paginated: `GET /trash` still returns a cursor, but this screen fetches a single page
 * (the server's own default limit) and stops there — a trash list beyond one page's
 * worth of trashed assets is an edge case this step's "Done when" doesn't call for, and
 * nothing else in the app needs a trash `RemoteMediator`/Room cache the way the timeline
 * does. Doesn't fetch in `init` — [TrashScreen] triggers [load] itself via
 * `LaunchedEffect(Unit)`, the same "screen decides when to kick off its own first load"
 * convention `FoldersViewModel` already uses (there via `onPermissionGranted()`), which
 * also keeps this class free of a network side effect at construction time.
 */
@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<TrashUiState>(TrashUiState.Loading)
        val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

        val cdnHost: StateFlow<String?> =
            instanceStore.current
                .map { it?.host }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun load() {
            _uiState.value = TrashUiState.Loading
            viewModelScope.launch {
                _uiState.value =
                    try {
                        val instance = instanceStore.current.first() ?: throw IOException("no connected instance")
                        val api =
                            archivistApiFactory.create(
                                instance.host,
                                instance.document.region,
                                instance.document.cognito.clientId,
                            )
                        val response = api.getTrash("${instance.document.apiBase}/trash")
                        TrashUiState.Loaded(response.items.map { it.toItem() })
                    } catch (e: IOException) {
                        TrashUiState.Error(e.message ?: "network error")
                    } catch (e: HttpException) {
                        TrashUiState.Error("HTTP ${e.code()}")
                    }
            }
        }
    }

private fun TrashEntryDto.toItem() =
    TrashItem(
        photoId = photoId,
        takenAt = takenAt,
        tzOffsetMin = tzOffsetMin,
        thumbs = thumbs.mapKeys { it.key.toInt() },
        encDek = encDek,
        blockedAttempts = blockedAttempts,
        lastAttemptAt = lastAttemptAt,
        lastAttemptBy = lastAttemptBy,
    )
