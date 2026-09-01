package fr.enry.archivist.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.PhotoEntity
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.data.repo.PhotoRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One grid cell — either a photo or a date header inserted ahead of the first photo
 * of a new *local* day (`tzOffsetMin`, not UTC — plan step 2.11's own "Done when").
 * [Header.anchorPhotoId] — the photoId of the photo immediately following this header —
 * exists purely so the grid can give each header a globally unique key (see
 * `TimelineScreen.kt`): the same calendar [Header.date] can legitimately recur
 * *non-adjacently* in the list, because the list is sorted by UTC `takenAt` but grouped
 * by *local* day, and those two orderings aren't monotonic with each other once photos
 * carry different `tzOffsetMin` values (e.g. one photo at UTC 23:30 with a +02:00
 * offset lands on the *next* local day, sorting ahead of an earlier-UTC photo that's
 * still on the *previous* local day) — confirmed for real, not hypothetically: a
 * 1,000-photo live run against the `dev` instance crashed Compose with "Key
 * 'header-2026-08-22' was already used" the first time two same-date headers landed
 * in one loaded window, before this field existed. */
sealed interface TimelineItem {
    data class Photo(val photo: PhotoEntity) : TimelineItem

    data class Header(val date: LocalDate, val anchorPhotoId: String) : TimelineItem
}

internal fun PhotoEntity.localDate(): LocalDate =
    Instant.parse(takenAt).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).toLocalDate()

/** Inserts a [TimelineItem.Header] ahead of the first photo of each new *local* day —
 * pulled out of [TimelineViewModel] as a standalone function so it's testable directly
 * against a fake [PagingSource][androidx.paging.PagingSource]/`Pager`, with no
 * `PhotoRepository`/Hilt/Room in the loop. */
internal fun Flow<PagingData<PhotoEntity>>.toTimelineItems(): Flow<PagingData<TimelineItem>> =
    map { pagingData ->
        pagingData
            .map<PhotoEntity, TimelineItem> { TimelineItem.Photo(it) }
            .insertSeparators { before, after ->
                val afterPhoto = (after as? TimelineItem.Photo)?.photo ?: return@insertSeparators null
                val afterDate = afterPhoto.localDate()
                val beforeDate = (before as? TimelineItem.Photo)?.photo?.localDate()
                if (beforeDate != afterDate) TimelineItem.Header(afterDate, afterPhoto.photoId) else null
            }
    }

/**
 * Plan step 2.11. [locked] gates the whole screen off the grid the moment the master
 * key disappears — including mid-session, from `ArchivistApplication.onTrimMemory` —
 * per "Locked state" in android.md: the timeline is metadata and renders fine with no
 * key at all, so without this the app would look healthy while every thumbnail failed
 * to decrypt. This is also what actually fixes the staleness plan step 2.5's own
 * STATUS.md note flagged: `MainActivity`'s local `unlocked` boolean never re-checks
 * [MasterKeyHolder] after the first unlock, but this screen does, continuously.
 */
@HiltViewModel
class TimelineViewModel
    @Inject
    constructor(
        photoRepository: PhotoRepository,
        masterKeyHolder: MasterKeyHolder,
        instanceStore: InstanceStore,
    ) : ViewModel() {
        val locked: StateFlow<Boolean> =
            masterKeyHolder.current
                .map { it == null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), masterKeyHolder.current.value == null)

        /** The CDN host thumbnail URLs are built against — `null` only in the
         * impossible-in-practice case of reaching this screen with no connected
         * instance, in which case the grid just shows placeholders. */
        val cdnHost: StateFlow<String?> =
            instanceStore.current
                .map { it?.host }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val timeline: Flow<PagingData<TimelineItem>> =
            photoRepository.timeline()
                .toTimelineItems()
                .cachedIn(viewModelScope)
    }
