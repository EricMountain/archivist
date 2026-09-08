package fr.enry.archivist.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.MainActivity
import fr.enry.archivist.R
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.repo.UploadOutcome
import fr.enry.archivist.data.repo.UploadRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit

private const val KEY_QUEUE_ID = "queueId"
private const val NOTIFICATION_CHANNEL_ID = "uploads"

/** Common tag on every [UploadWorker] request, existing solely so
 * [WorkManagerUploadScheduler.cancelAll] can stop every enqueued-or-running upload in
 * one call — [UploadWorker.uniqueWorkName] already gives each row its own unique work
 * name for [UploadScheduler.cancel]'s per-row case, but WorkManager has no
 * "cancel every unique work name matching a prefix", only cancel-by-tag. */
private const val UPLOAD_WORK_TAG = "upload"

/** One notification for the whole batch, deliberately shared across every concurrently
 * running [UploadWorker] rather than one id per file (tried, and reverted — see
 * `docs/plans/STATUS.md`'s 2026-09-08 notes on this file for the two failed shapes:
 * a single id with *per-file* content flips between whichever worker posted last, and
 * one id *per file* pops a separate tray entry in and out for every file, which reads
 * as constant motion once a long queue is moving several files at a time under
 * [UploadConcurrencyLimiter]). [buildProgressNotification]'s content is aggregate
 * (queue depth only, no per-file name) precisely so that whichever worker happens to
 * (re)post it next shows the same coherent picture instead of racing a sibling's
 * stale snapshot. */
private const val NOTIFICATION_ID = 4201
private const val NEEDS_UNLOCK_NOTIFICATION_CHANNEL_ID = "needs-unlock"

/** Fixed, distinct from [NOTIFICATION_ID]: every retry of every queued file re-derives
 * the *same* outcome while the key is missing, and [android.app.Notification.Builder.setOnlyAlertOnce]
 * needs a stable id to actually stay "only once" rather than re-alerting per file/retry. */
private const val NEEDS_UNLOCK_NOTIFICATION_ID = 4202

/** The seam between [fr.enry.archivist.ui.settings.FoldersViewModel] (and anything
 * else that queues uploads) and WorkManager itself — same role
 * [fr.enry.archivist.sync.MediaStoreSource]/[Thumbnailer] play for their own platform
 * APIs: a fake stands in for tests, since a bare JVM test has no real WorkManager to
 * enqueue against. `suspend` since plan step 2.14: building each request's
 * [androidx.work.Constraints] now reads [SyncSettingsStore]. */
interface UploadScheduler {
    suspend fun enqueueAll(queueIds: List<Long>)

    /** Plan step 2.15: "cancellable" (`android.md`'s Screens section). Cancelling the
     * WorkManager job alone would leave the row sitting in the queue forever (nothing
     * else ever removes it) -- [fr.enry.archivist.ui.queue.QueueViewModel] deletes the
     * row itself right after calling this. */
    fun cancel(queueId: Long)

    /** The Settings > Sync "Pause uploads" toggle's other half — cancels every
     * enqueued-or-running upload job without touching `upload_queue` itself, unlike
     * [cancel]: resuming needs [fr.enry.archivist.data.local.db.UploadQueueDao.getActiveIds]
     * to still find these rows so it can re-[enqueueAll] them. */
    fun cancelAll()
}

class WorkManagerUploadScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncSettingsStore: SyncSettingsStore,
    ) : UploadScheduler {
        /** The single seam every enqueue path goes through (a fresh scan, app
         * startup's re-enqueue, a per-row retry) — so `uploadsPaused` gates all of
         * them at once by simply declining to schedule anything, rather than each
         * caller having to check the setting itself. */
        override suspend fun enqueueAll(queueIds: List<Long>) {
            val settings = syncSettingsStore.settings.first()
            if (settings.uploadsPaused) return
            UploadWorker.enqueueAll(context, queueIds, settings)
        }

        override fun cancel(queueId: Long) = UploadWorker.cancel(context, queueId)

        override fun cancelAll() = UploadWorker.cancelAll(context)
    }

/**
 * Plan step 2.10: one WorkManager work item per queued file — [uniqueWorkName] makes a
 * re-enqueue of the same row ([UploadRepository.activeQueueIds]'s job, e.g. after a
 * fresh scan or an app restart) a no-op via [ExistingWorkPolicy.KEEP] rather than a
 * duplicate.
 *
 * The actual upload logic lives in [UploadRepository] — this class is WorkManager
 * plumbing around it: constraints, backoff, the foreground notification, mapping
 * [UploadOutcome] onto [Result], and (2026-09-07) a separate low-priority notification
 * for [UploadOutcome.NeedsUnlock] — the master key is no longer cleared just for being
 * backgrounded (see `ArchivistApplication`'s own doc), so in practice this only fires
 * right after a fresh sign-in/enrolment hasn't happened yet, not on every ordinary
 * background cycle. The two notifications are independently controllable: separate
 * channels (a user can silence either from system settings without the other), and
 * separate `SyncSettings` toggles (`notifyWhenUploadNeedsUnlock`,
 * `showUploadProgressNotification`) — since `POST_NOTIFICATIONS` itself is one
 * all-or-nothing OS permission with no way to split at that layer (see plan step 2.19's
 * `PermissionOnboardingScreen`, which is what actually requests it).
 *
 * **`uploadAsForegroundService` (2026-09-08) picks which of two ways this worker keeps
 * itself alive during a large transfer.** Foreground (the default): more reliable,
 * since Android is much less willing to defer or kill a foreground job under memory
 * pressure, but its notification is mandatory — an OS requirement, not something this
 * app chooses. Background: an ordinary `CoroutineWorker`, at real (if smaller) risk of
 * being deferred or killed mid-upload, but its progress notification becomes genuinely
 * optional (`showUploadProgressNotification`), since nothing about a plain background
 * job requires one.
 */
@HiltWorker
class UploadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val uploadRepository: UploadRepository,
        private val uploadQueueDao: UploadQueueDao,
        private val syncSettingsStore: SyncSettingsStore,
        private val uploadConcurrencyLimiter: UploadConcurrencyLimiter,
    ) : CoroutineWorker(appContext, params) {
        /** The [UploadConcurrencyLimiter] permit is held for this entire function body —
         * a row waiting on one stays at whatever state it already had (`PENDING` for a
         * fresh one) and gets no notification of its own, so only up to
         * [UploadConcurrencyLimiter.MAX_CONCURRENT_UPLOADS] files are ever actually
         * mid-upload (and thus holding real memory — file buffers, thumbnails, crypto
         * state) at once, regardless of how many `UploadWorker`s WorkManager itself has
         * started. See that class's own doc for why this can't be done via WorkManager's
         * own scheduler configuration instead. */
        override suspend fun doWork(): Result =
            uploadConcurrencyLimiter.semaphore.withPermit {
                val queueId = inputData.getLong(KEY_QUEUE_ID, -1L)
                if (queueId < 0) return@withPermit Result.failure()

                // Settings.uploadAsForegroundService picks the mechanism; only the
                // background path needs its own cleanup below -- WorkManager dismisses a
                // foreground notification itself once setForeground's window ends, but a
                // plain NotificationManagerCompat.notify() here has no such owner.
                val settings = syncSettingsStore.settings.first()
                if (settings.uploadAsForegroundService) {
                    setForeground(foregroundInfo())
                } else if (settings.showUploadProgressNotification) {
                    postProgressNotification()
                }

                try {
                    when (val outcome = uploadRepository.uploadOne(queueId)) {
                        UploadOutcome.Success -> {
                            cancelNeedsUnlockNotification()
                            Result.success()
                        }
                        UploadOutcome.Retry -> Result.retry()
                        UploadOutcome.NeedsUnlock -> {
                            maybeNotifyNeedsUnlock()
                            Result.retry()
                        }
                        is UploadOutcome.PermanentFailure -> Result.failure(workDataOf("error" to outcome.message))
                    }
                } finally {
                    if (!settings.uploadAsForegroundService) cancelProgressNotification()
                }
            }

        /** Per the Sync settings toggle (default on) -- a low-priority, alert-once
         * notification, not the ongoing foreground one above: this fires from
         * *outside* the foreground-service window (WorkManager already gave up on this
         * attempt by the time [UploadOutcome.NeedsUnlock] comes back) and can span many
         * backoff retries, possibly each a fresh `UploadWorker` instance with no memory
         * of the last one -- `setOnlyAlertOnce(true)` against the same fixed id is what
         * keeps re-posting it on every retry from re-alerting (sound/vibrate/heads-up)
         * more than once. */
        private suspend fun maybeNotifyNeedsUnlock() {
            if (!syncSettingsStore.settings.first().notifyWhenUploadNeedsUnlock) return
            val context = applicationContext
            createNeedsUnlockNotificationChannelIfNeeded(context)

            val openApp =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat.Builder(context, NEEDS_UNLOCK_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.needs_unlock_notification_title))
                    .setContentText(context.getString(R.string.needs_unlock_notification_text))
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(openApp)
                    .build()
            NotificationManagerCompat.from(context).notify(NEEDS_UNLOCK_NOTIFICATION_ID, notification)
        }

        private fun cancelNeedsUnlockNotification() {
            NotificationManagerCompat.from(applicationContext).cancel(NEEDS_UNLOCK_NOTIFICATION_ID)
        }

        private fun createNeedsUnlockNotificationChannelIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    NEEDS_UNLOCK_NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.needs_unlock_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            manager.createNotificationChannel(channel)
        }

        /** One notification for the whole batch (see [NOTIFICATION_ID]'s doc for why
         * this shows aggregate progress rather than naming the current file) —
         * "long-running worker with a foreground notification for large files, or
         * Android kills it" (the plan's own words), extended to cover a long queue of
         * small files running long in aggregate too. Shared between [foregroundInfo]
         * and [postProgressNotification] — same content either way, only how it's
         * delivered to the system differs. Content text is the queue depth
         * ([UploadQueueDao.observeRemainingCount]) — same number Settings > Sync
         * shows — read fresh on every call, so whichever concurrently-running worker
         * (re)posts this next always shows the current true count rather than a stale
         * snapshot from whenever it started. */
        private suspend fun buildProgressNotification(): Notification {
            val context = applicationContext
            createNotificationChannelIfNeeded(context)

            val remaining = uploadQueueDao.observeRemainingCount().first()
            val openApp =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.upload_notification_title))
                .setContentText(context.resources.getQuantityString(R.plurals.upload_queue_depth, remaining, remaining))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build()
        }

        private suspend fun foregroundInfo(): ForegroundInfo {
            val notification = buildProgressNotification()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        /** The background-mode equivalent of [foregroundInfo] — an ordinary, non-foreground
         * notification, so it has no automatic lifecycle of its own; [cancelProgressNotification]
         * is what removes it once this attempt finishes, success or not. */
        private suspend fun postProgressNotification() {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, buildProgressNotification())
        }

        private fun cancelProgressNotification() {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }

        private fun createNotificationChannelIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.upload_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            manager.createNotificationChannel(channel)
        }

        companion object {
            private fun uniqueWorkName(queueId: Long) = "upload-$queueId"

            /** Constraints from settings (plan step 2.14's Sync section) —
             * `setRequiresBatteryNotLow` stays hardcoded `true` regardless: the plan
             * text only calls out network policy and charging as settings, not this
             * one. `NetworkType.CONNECTED` (any network) rather than `NetworkType.METERED`
             * when metered is allowed — WorkManager has no "unmetered-or-metered but
             * not none" constraint, and "allow metered" is meant to mean "don't
             * require Wi-Fi", not "require a metered connection specifically". */
            private fun buildRequest(
                queueId: Long,
                settings: SyncSettings,
            ) = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_QUEUE_ID to queueId))
                .addTag(UPLOAD_WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (settings.allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresCharging(settings.requiresCharging)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

            fun enqueue(
                context: Context,
                queueId: Long,
                settings: SyncSettings,
            ) {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(uniqueWorkName(queueId), ExistingWorkPolicy.KEEP, buildRequest(queueId, settings))
            }

            fun enqueueAll(
                context: Context,
                queueIds: List<Long>,
                settings: SyncSettings,
            ) {
                queueIds.forEach { enqueue(context, it, settings) }
            }

            fun cancel(
                context: Context,
                queueId: Long,
            ) {
                WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(queueId))
            }

            fun cancelAll(context: Context) {
                WorkManager.getInstance(context).cancelAllWorkByTag(UPLOAD_WORK_TAG)
            }
        }
    }
