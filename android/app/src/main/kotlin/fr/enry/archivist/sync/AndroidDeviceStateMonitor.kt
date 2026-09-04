package fr.enry.archivist.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * `registerDefaultNetworkCallback` reports the network the system would actually route
 * uploads through (unaffected by other apps' bound networks), and `ACTION_BATTERY_LOW`/
 * `ACTION_BATTERY_OKAY` are the same system-declared low-battery signal
 * `setRequiresBatteryNotLow` itself is defined against, rather than a hand-picked
 * percentage threshold that could drift from what WorkManager actually enforces.
 */
@Singleton
class AndroidDeviceStateMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DeviceStateMonitor {
        override val state: Flow<DeviceState> =
            callbackFlow {
                val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

                var connected = false
                var metered = true
                var charging = false
                var batteryLow = false

                fun emitState() = trySend(DeviceState(connected, metered, charging, batteryLow))

                fun readNetwork(network: Network?) {
                    val capabilities = network?.let { connectivityManager?.getNetworkCapabilities(it) }
                    connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    metered = capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    emitState()
                }

                val networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = readNetwork(network)

                        override fun onLost(network: Network) = readNetwork(connectivityManager?.activeNetwork)

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) = readNetwork(network)
                    }
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
                readNetwork(connectivityManager?.activeNetwork)

                val batteryReceiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            receiverContext: Context,
                            intent: Intent,
                        ) {
                            when (intent.action) {
                                Intent.ACTION_BATTERY_LOW -> batteryLow = true
                                Intent.ACTION_BATTERY_OKAY -> batteryLow = false
                                Intent.ACTION_BATTERY_CHANGED -> {
                                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                                    charging =
                                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                        status == BatteryManager.BATTERY_STATUS_FULL
                                }
                            }
                            emitState()
                        }
                    }
                val filter =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_BATTERY_LOW)
                        addAction(Intent.ACTION_BATTERY_OKAY)
                        // Sticky -- registering for this alone also delivers the
                        // current charging state immediately, no separate initial read
                        // needed.
                        addAction(Intent.ACTION_BATTERY_CHANGED)
                    }
                context.registerReceiver(batteryReceiver, filter)

                awaitClose {
                    connectivityManager?.unregisterNetworkCallback(networkCallback)
                    context.unregisterReceiver(batteryReceiver)
                }
            }.distinctUntilChanged()
    }
