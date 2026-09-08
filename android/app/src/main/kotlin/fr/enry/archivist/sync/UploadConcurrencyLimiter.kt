package fr.enry.archivist.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore

/**
 * Caps how many [UploadWorker]s actually process a file at once, independent of how
 * many WorkManager itself has started running `doWork()`. WorkManager's own scheduler
 * concurrency has a floor of [androidx.work.Configuration.MIN_SCHEDULER_LIMIT] (20)
 * that the public `Configuration.Builder.setMaxSchedulerLimit` API refuses to go below,
 * so gating inside the worker is the only way to cap real concurrent uploads at
 * [MAX_CONCURRENT_UPLOADS] — found live-debugging a queue of tens to hundreds of files
 * all showing `Uploading` at once, each holding its own file buffers/thumbnails/crypto
 * state in memory simultaneously, which crashed the app. A file waiting on a permit
 * here stays in whatever state it was already in (`PENDING` for a fresh row) and shows
 * no notification of its own — only the [MAX_CONCURRENT_UPLOADS] permit holders do —
 * since [UploadWorker.doWork] acquires this before building either.
 *
 * `@Singleton`: every [UploadWorker] instance Hilt creates must share the same
 * [Semaphore], not each get its own — a per-instance semaphore would cap nothing.
 */
@Singleton
class UploadConcurrencyLimiter
    @Inject
    constructor() {
        val semaphore = Semaphore(MAX_CONCURRENT_UPLOADS)

        companion object {
            const val MAX_CONCURRENT_UPLOADS = 2
        }
    }
