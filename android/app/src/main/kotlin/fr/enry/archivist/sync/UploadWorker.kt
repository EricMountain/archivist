package fr.enry.archivist.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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

private const val KEY_QUEUE_ID = "queueId"
private const val NOTIFICATION_CHANNEL_ID = "uploads"
private const val NOTIFICATION_ID = 4201

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
}

class WorkManagerUploadScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncSettingsStore: SyncSettingsStore,
    ) : UploadScheduler {
        override suspend fun enqueueAll(queueIds: List<Long>) =
            UploadWorker.enqueueAll(context, queueIds, syncSettingsStore.settings.first())

        override fun cancel(queueId: Long) = UploadWorker.cancel(context, queueId)
    }

/**
 * Plan step 2.10: one WorkManager work item per queued file — [uniqueWorkName] makes a
 * re-enqueue of the same row ([UploadRepository.activeQueueIds]'s job, e.g. after a
 * fresh scan or an app restart) a no-op via [ExistingWorkPolicy.KEEP] rather than a
 * duplicate.
 *
 * The actual upload logic lives in [UploadRepository] — this class is WorkManager
 * plumbing around it: constraints, backoff, the foreground notification, and mapping
 * [UploadOutcome] onto [Result].
 */
@HiltWorker
class UploadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val uploadRepository: UploadRepository,
        private val uploadQueueDao: UploadQueueDao,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val queueId = inputData.getLong(KEY_QUEUE_ID, -1L)
            if (queueId < 0) return Result.failure()

            setForeground(foregroundInfo(queueId))

            return when (val outcome = uploadRepository.uploadOne(queueId)) {
                UploadOutcome.Success -> Result.success()
                UploadOutcome.Retry -> Result.retry()
                is UploadOutcome.PermanentFailure -> Result.failure(workDataOf("error" to outcome.message))
            }
        }

        /** One notification per in-flight file — "long-running worker with a
         * foreground notification for large files, or Android kills it" (the plan's
         * own words). Shown for every file, not just large ones: a queue of many small
         * files can run long in aggregate too, and there's no reliable way to know a
         * file is "large" before [UploadRepository] has already read it. */
        private suspend fun foregroundInfo(queueId: Long): ForegroundInfo {
            val context = applicationContext
            createNotificationChannelIfNeeded(context)

            val displayName = uploadQueueDao.getById(queueId)?.displayName ?: context.getString(R.string.app_name)
            val openApp =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.upload_notification_title, displayName))
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .setContentIntent(openApp)
                    .build()

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
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
        }
    }
