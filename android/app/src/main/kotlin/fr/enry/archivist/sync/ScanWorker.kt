package fr.enry.archivist.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.SyncSettingsStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val KEY_REARM = "rearm"
private const val WORK_ON_CHANGE = "scan-on-change"
private const val WORK_NOW = "scan-now"
private const val WORK_PERIODIC = "scan-periodic"

/** A burst of photos (a camera burst, a bulk import) is one scan, not one per file. */
private const val TRIGGER_DELAY_SECONDS = 10L
private const val TRIGGER_MAX_DELAY_SECONDS = 60L
private const val BACKSTOP_INTERVAL_HOURS = 1L

/** How new device photos get discovered without the user opening Settings > Folders. */
interface ScanScheduler {
    /** Idempotent -- safe to call on every app start. Arms the MediaStore content-URI
     * trigger and the periodic backstop. */
    suspend fun arm()

    /** One scan as soon as constraints allow (app start/foreground). Collapses with a
     * scan already pending. */
    suspend fun scanNow()
}

class WorkManagerScanScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncSettingsStore: SyncSettingsStore,
    ) : ScanScheduler {
        override suspend fun arm() {
            ScanWorker.armTrigger(context, syncSettingsStore.settings.first(), ExistingWorkPolicy.KEEP)
            ScanWorker.armPeriodic(context, syncSettingsStore.settings.first())
        }

        override suspend fun scanNow() {
            val request =
                OneTimeWorkRequestBuilder<ScanWorker>()
                    .setConstraints(uploadConstraints(syncSettingsStore.settings.first()))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }

/**
 * Discovers new photos on the device and queues them: [ScanCoordinator.scanAndEnqueue].
 * Three entry points, all this class:
 *  * **on change** -- a one-shot work request with a MediaStore content-URI trigger
 *    (images and video). Android wakes the app when a photo is added, even with the
 *    process dead. The trigger fires once, so this re-arms itself after every run.
 *  * **periodic backstop** -- triggers can be missed (a reboot, a force-stop, a change
 *    mid-run), and periodic work can't carry a content trigger, so this just scans.
 *  * **now** -- app start/foreground.
 *
 * Constraints are the same network/charging/battery ones as uploads
 * ([uploadConstraints]): the scan hashes whole files and needs the network for the hash
 * secret, and there's no point finding files that can't be uploaded yet. Skipped
 * silently (still re-armed) while uploads are paused or media access isn't granted.
 * A force-stopped app gets no triggers until the user next opens it -- an Android
 * limitation, covered by the app-start scan.
 */
@HiltWorker
class ScanWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val scanCoordinator: ScanCoordinator,
        private val syncSettingsStore: SyncSettingsStore,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val settings = syncSettingsStore.settings.first()
            if (!settings.uploadsPaused && hasMediaAccess(applicationContext)) {
                try {
                    scanCoordinator.scanAndEnqueue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed scan isn't retried with backoff: the next trigger, the
                    // periodic backstop or an app start scans again anyway.
                }
            }
            if (inputData.getBoolean(KEY_REARM, false)) {
                armTrigger(applicationContext, settings, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            return Result.success()
        }

        companion object {
            /** APPEND_OR_REPLACE when called from a running trigger worker: REPLACE would
             * cancel that very worker, KEEP would be a no-op while it's still running. */
            internal fun armTrigger(
                context: Context,
                settings: SyncSettings,
                policy: ExistingWorkPolicy,
            ) {
                val constraints =
                    Constraints.Builder(uploadConstraints(settings))
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(TRIGGER_DELAY_SECONDS, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
                        .build()
                val request =
                    OneTimeWorkRequestBuilder<ScanWorker>()
                        .setInputData(workDataOf(KEY_REARM to true))
                        .setConstraints(constraints)
                        .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_ON_CHANGE, policy, request)
            }

            internal fun armPeriodic(
                context: Context,
                settings: SyncSettings,
            ) {
                val request =
                    PeriodicWorkRequestBuilder<ScanWorker>(BACKSTOP_INTERVAL_HOURS, TimeUnit.HOURS)
                        .setConstraints(uploadConstraints(settings))
                        .build()
                // UPDATE (not KEEP) so a changed network/charging setting reaches it at
                // the next app start.
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    }

/** Any one of the media permissions is enough to scan something: API 34's partial
 * access (`READ_MEDIA_VISUAL_USER_SELECTED`) lists just the picked files. */
private fun hasMediaAccess(context: Context): Boolean {
    val permissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    return permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
