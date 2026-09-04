package fr.enry.archivist.sync

import fr.enry.archivist.data.local.SyncSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceStateMonitorTest {
    private val connectedUnmetered = DeviceState(isConnected = true, isMetered = false, isCharging = false, isBatteryLow = false)

    @Test
    fun `no network takes priority over every other reason`() {
        val state = connectedUnmetered.copy(isConnected = false, isMetered = true, isBatteryLow = true)

        assertEquals(QueueIdleReason.NO_NETWORK, queueIdleReason(SyncSettings(requiresCharging = true), state))
    }

    @Test
    fun `a metered connection is fine once the setting allows it`() {
        val metered = connectedUnmetered.copy(isMetered = true)

        assertEquals(QueueIdleReason.WAITING_FOR_WIFI, queueIdleReason(SyncSettings(allowMeteredNetwork = false), metered))
        assertEquals(QueueIdleReason.NONE, queueIdleReason(SyncSettings(allowMeteredNetwork = true), metered))
    }

    @Test
    fun `not charging only matters when charging is required`() {
        val notCharging = connectedUnmetered.copy(isCharging = false)

        assertEquals(QueueIdleReason.NONE, queueIdleReason(SyncSettings(requiresCharging = false), notCharging))
        assertEquals(QueueIdleReason.WAITING_TO_CHARGE, queueIdleReason(SyncSettings(requiresCharging = true), notCharging))
    }

    @Test
    fun `charging satisfies the charging requirement`() {
        val charging = connectedUnmetered.copy(isCharging = true)

        assertEquals(QueueIdleReason.NONE, queueIdleReason(SyncSettings(requiresCharging = true), charging))
    }

    @Test
    fun `low battery is always a reason -- setRequiresBatteryNotLow is never a setting`() {
        val batteryLow = connectedUnmetered.copy(isBatteryLow = true)

        assertEquals(QueueIdleReason.WAITING_FOR_BATTERY, queueIdleReason(SyncSettings(), batteryLow))
    }

    @Test
    fun `nothing blocking reports NONE`() {
        assertEquals(QueueIdleReason.NONE, queueIdleReason(SyncSettings(), connectedUnmetered))
    }
}
