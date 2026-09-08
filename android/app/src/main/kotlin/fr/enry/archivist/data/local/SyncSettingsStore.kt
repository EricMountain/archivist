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

        private companion object {
            val ALLOW_METERED_KEY = booleanPreferencesKey("allow_metered_network")
            val REQUIRES_CHARGING_KEY = booleanPreferencesKey("requires_charging")
            val NOTIFY_WHEN_LOCKED_KEY = booleanPreferencesKey("notify_when_upload_needs_unlock")
            val FOREGROUND_SERVICE_KEY = booleanPreferencesKey("upload_as_foreground_service")
            val SHOW_UPLOAD_NOTIFICATION_KEY = booleanPreferencesKey("show_upload_progress_notification")
        }
    }
