package fr.enry.archivist.sync

import fr.enry.archivist.data.local.SyncSettings
import kotlinx.coroutines.flow.Flow

/**
 * The live device conditions [UploadWorker]'s own [androidx.work.Constraints] are built
 * from (network, metered-ness, charging, battery) -- plan step 2.15 needs to explain
 * *why* the queue isn't moving, and WorkManager itself doesn't expose that reasoning
 * (a constrained work item just sits `ENQUEUED` with no reason attached). Its own
 * interface/impl split mirrors [MediaStoreSource]/[Thumbnailer]: a fake stands in for
 * tests, since a bare JVM test has no `ConnectivityManager`/`BatteryManager` to read.
 */
data class DeviceState(
    val isConnected: Boolean,
    val isMetered: Boolean,
    val isCharging: Boolean,
    val isBatteryLow: Boolean,
)

interface DeviceStateMonitor {
    val state: Flow<DeviceState>
}

/** Why [fr.enry.archivist.ui.queue.QueueScreen]'s queue isn't currently making progress
 * -- mirrors exactly the constraints [UploadWorker.buildRequest] sets from [SyncSettings]
 * (network policy, charging) plus the one that's always on (`setRequiresBatteryNotLow`).
 * Checked in this order because a genuinely offline device should say so rather than
 * "waiting for Wi-Fi", which implies a metered connection is actually present. */
enum class QueueIdleReason {
    NONE,
    NO_NETWORK,
    WAITING_FOR_WIFI,
    WAITING_TO_CHARGE,
    WAITING_FOR_BATTERY,
}

fun queueIdleReason(
    settings: SyncSettings,
    state: DeviceState,
): QueueIdleReason =
    when {
        !state.isConnected -> QueueIdleReason.NO_NETWORK
        state.isMetered && !settings.allowMeteredNetwork -> QueueIdleReason.WAITING_FOR_WIFI
        settings.requiresCharging && !state.isCharging -> QueueIdleReason.WAITING_TO_CHARGE
        state.isBatteryLow -> QueueIdleReason.WAITING_FOR_BATTERY
        else -> QueueIdleReason.NONE
    }
