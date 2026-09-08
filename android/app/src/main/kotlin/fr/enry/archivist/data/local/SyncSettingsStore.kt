package fr.enry.archivist.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SyncSettings(
    val allowMeteredNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    /** 2026-09-07: whether [fr.enry.archivist.sync.UploadWorker] posts a low-priority
     * notification when a queued upload can't proceed because the master key isn't in
     * memory yet (see `UploadOutcome.NeedsUnlock`'s own doc). Defaults on — the
     * alternative is a queue that silently retries-and-fails with no visible signal at
     * all. */
    val notifyWhenUploadNeedsUnlock: Boolean = true,
    /** 2026-09-08: whether [fr.enry.archivist.sync.UploadWorker] runs as a foreground
     * service. Defaults on — more reliable for a large file, since Android is far more
     * willing to defer or kill an ordinary background job under memory pressure than a
     * foreground one. A foreground service must carry a visible notification; that's
     * an OS requirement, not a choice this setting makes, so [showUploadProgressNotification]
     * has no effect while this is on. */
    val uploadAsForegroundService: Boolean = true,
    /** 2026-09-08: whether [fr.enry.archivist.sync.UploadWorker] shows a progress
     * notification while running as an ordinary background job — meaningless, and
     * ignored, while [uploadAsForegroundService] is on, since a foreground service's
     * notification isn't optional. Defaults on so a background upload doesn't go
     * silent by default; turning it off trades that visibility away for one less
     * notification, with no effect on reliability either way. */
    val showUploadProgressNotification: Boolean = true,
    /** 2026-09-08: per the user's explicit request for a manual pause from the
     * Settings screen — independent of every constraint above, which only ever pause
     * uploads on a *device condition* (no Wi-Fi, not charging). Read by
     * [fr.enry.archivist.sync.WorkManagerUploadScheduler.enqueueAll], which is the
     * single seam every enqueue path (a fresh scan, app startup's re-enqueue of
     * [fr.enry.archivist.data.local.db.UploadQueueDao.getActiveIds], a per-row retry)
     * already goes through — so nothing new starts while this is on. Toggling it off
     * is what actually resumes anything already queued; toggling it on cancels
     * whatever's enqueued or running via
     * [fr.enry.archivist.sync.UploadScheduler.cancelAll] without touching the queue
     * rows themselves, so resuming finds exactly what was left. Defaults off — an
     * upload queue that silently never starts would otherwise look identical to one
     * that's just idle. */
    val uploadsPaused: Boolean = false,
)

/**
 * Plan step 2.14's Settings > Sync section — "network policy, charging requirement".
 * Read by [fr.enry.archivist.sync.UploadWorker] to build each work item's
 * [androidx.work.Constraints] (previously hardcoded — see that class's own note on what
 * it used before this existed); written by the Sync settings screen. `DataStore<Preferences>`,
 * same choice as [InstanceStore] and for the same reason: this is read from a
 * `CoroutineWorker`, never from a synchronous/background-thread call the way
 * [TokenStore]'s plain `SharedPreferences` needs to be.
 *
 * `setRequiresBatteryNotLow` stays hardcoded `true` in [fr.enry.archivist.sync.UploadWorker] —
 * the plan text doesn't call it out as a setting, only network policy and charging.
 */
class SyncSettingsStore
    @Inject
    constructor(
        @SyncSettingsDataStore private val dataStore: DataStore<Preferences>,
    ) {
        val settings: Flow<SyncSettings> =
            dataStore.data.map { prefs ->
                SyncSettings(
                    allowMeteredNetwork = prefs[ALLOW_METERED_KEY] ?: false,
                    requiresCharging = prefs[REQUIRES_CHARGING_KEY] ?: false,
                    notifyWhenUploadNeedsUnlock = prefs[NOTIFY_WHEN_LOCKED_KEY] ?: true,
                    uploadAsForegroundService = prefs[FOREGROUND_SERVICE_KEY] ?: true,
                    showUploadProgressNotification = prefs[SHOW_UPLOAD_NOTIFICATION_KEY] ?: true,
                    uploadsPaused = prefs[UPLOADS_PAUSED_KEY] ?: false,
                )
            }

        suspend fun setAllowMeteredNetwork(allow: Boolean) {
            dataStore.edit { it[ALLOW_METERED_KEY] = allow }
        }

        suspend fun setRequiresCharging(requires: Boolean) {
            dataStore.edit { it[REQUIRES_CHARGING_KEY] = requires }
        }

        suspend fun setNotifyWhenUploadNeedsUnlock(notify: Boolean) {
            dataStore.edit { it[NOTIFY_WHEN_LOCKED_KEY] = notify }
        }

        suspend fun setShowUploadProgressNotification(show: Boolean) {
            dataStore.edit { it[SHOW_UPLOAD_NOTIFICATION_KEY] = show }
        }

        suspend fun setUploadAsForegroundService(foreground: Boolean) {
            dataStore.edit { it[FOREGROUND_SERVICE_KEY] = foreground }
        }

        suspend fun setUploadsPaused(paused: Boolean) {
            dataStore.edit { it[UPLOADS_PAUSED_KEY] = paused }
        }

        private companion object {
            val ALLOW_METERED_KEY = booleanPreferencesKey("allow_metered_network")
            val REQUIRES_CHARGING_KEY = booleanPreferencesKey("requires_charging")
            val NOTIFY_WHEN_LOCKED_KEY = booleanPreferencesKey("notify_when_upload_needs_unlock")
            val FOREGROUND_SERVICE_KEY = booleanPreferencesKey("upload_as_foreground_service")
            val SHOW_UPLOAD_NOTIFICATION_KEY = booleanPreferencesKey("show_upload_progress_notification")
            val UPLOADS_PAUSED_KEY = booleanPreferencesKey("uploads_paused")
        }
    }
