package fr.enry.archivist.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The only channel connecting [UploadRepository] to [PhotoRepository]/
 * [fr.enry.archivist.ui.timeline.TimelineViewModel]. Without it, the timeline has no way
 * to learn a photo finished uploading: [PhotoRepository.timeline]'s `photos` table is
 * only ever repopulated by a `RemoteMediator` `REFRESH`, and that only fires when the
 * `Pager` is first collected — i.e. on cold start, not when `upload_queue` drains. The
 * queue screen doesn't need this (`QueueViewModel` observes `upload_queue` directly,
 * which `UploadRepository.markDone` already writes to), but the timeline reads a
 * completely different table that the upload pipeline never touches.
 */
@Singleton
class UploadEvents
    @Inject
    constructor() {
        private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Emits once per [UploadRepository.markDone] call — i.e. once per queue row
         * that finishes (success, duplicate/attach, or a quietly-skipped purge tombstone),
         * not once per batch. A collector is expected to coalesce bursts itself (e.g. a
         * `LazyPagingItems.refresh()` mid-flight already supersedes an earlier one). */
        val completed: SharedFlow<Unit> = _completed

        fun notifyUploadCompleted() {
            _completed.tryEmit(Unit)
        }
    }
