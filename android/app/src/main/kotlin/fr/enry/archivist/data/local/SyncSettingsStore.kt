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

        private companion object {
            val ALLOW_METERED_KEY = booleanPreferencesKey("allow_metered_network")
            val REQUIRES_CHARGING_KEY = booleanPreferencesKey("requires_charging")
            val NOTIFY_WHEN_LOCKED_KEY = booleanPreferencesKey("notify_when_upload_needs_unlock")
        }
    }
