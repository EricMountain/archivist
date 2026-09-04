package fr.enry.archivist.testutil

import fr.enry.archivist.sync.DeviceState
import fr.enry.archivist.sync.DeviceStateMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** Stands in for [fr.enry.archivist.sync.AndroidDeviceStateMonitor] — no JVM unit test
 * environment has a real `ConnectivityManager`/`BatteryManager` to read. Defaults to
 * "everything's fine" (connected, unmetered, not charging, battery fine) so a test that
 * doesn't care about the idle-reason banner doesn't have to set one up. */
class FakeDeviceStateMonitor(
    initial: DeviceState = DeviceState(isConnected = true, isMetered = false, isCharging = false, isBatteryLow = false),
) : DeviceStateMonitor {
    private val flow = MutableStateFlow(initial)
    override val state = flow

    fun set(value: DeviceState) {
        flow.value = value
    }
}
