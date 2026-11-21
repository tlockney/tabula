package net.lockney.tabula

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import net.lockney.tabula.core.tlp.NetworkType
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.StateSource
import net.lockney.tabula.ha.mergeState

/**
 * The device's sensors as TLP state (packets P2.1 and P2.2): battery level,
 * charging, network type, Wi-Fi RSSI, the app version and the dashboard's
 * current page (F-NAV-02). Battery comes from the ACTION_BATTERY_CHANGED
 * sticky broadcast, network type from the system's default network, page
 * changes from the [DashboardBus], and every change goes out as a delta on
 * an unlimited channel (PROTOCOL 4.4). [close] unregisters the listeners.
 *
 * [extra] adds state owned elsewhere (screen control, P2.3) to the full
 * picture sent at registration; its changes arrive through [publish].
 */
class AndroidStateSource(
    context: Context,
    private val appVersion: String,
    private val dashboard: DashboardBus,
    private val extra: () -> StateDelta = { StateDelta() },
) : StateSource {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val channel = Channel<StateDelta>(Channel.UNLIMITED)

    private val batteryReceiver = BatteryReceiver()
    private val networkCallback = NetworkCallback()
    private val removePageListener: () -> Unit

    init {
        appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        removePageListener = dashboard.onPageChanged { path -> push(StateDelta(currentPage = path)) }
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        removePageListener()
        channel.close()
    }

    override fun current(): StateDelta {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var level: Int? = null
        var charging: Boolean? = null
        if (battery != null) {
            val raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) level = (raw * 100 / scale).coerceIn(0, 100)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        val own = StateDelta(
            batteryLevel = level,
            charging = charging,
            network = currentNetwork(),
            wifiRssi = currentRssi(),
            currentPage = dashboard.currentPage(),
            appVersion = appVersion,
        )
        return mergeState(extra(), own)
    }

    override val changes: ReceiveChannel<StateDelta> = channel

    private fun currentNetwork(): NetworkType {
        val network = connectivityManager?.activeNetwork ?: return NetworkType.NONE
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.NONE
        }
    }

    /** RSSI in dBm, or null while not on Wi-Fi or while the value is unreadable. */
    private fun currentRssi(): Int? {
        if (currentNetwork() != NetworkType.WIFI) return null
        val rssi = wifiManager?.connectionInfo?.rssi ?: return null
        return if (rssi <= -127 || rssi > 0) null else rssi.coerceIn(-127, 0)
    }

    /** Send a change; thread-safe (the channel is unlimited). */
    fun publish(delta: StateDelta) {
        if (delta.isNotEmpty) channel.trySend(delta)
    }

    private fun push(delta: StateDelta) = publish(delta)

    private inner class BatteryReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return
            push(
                StateDelta(
                    batteryLevel = (level * 100 / scale).coerceIn(0, 100),
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                ),
            )
        }
    }

    private inner class NetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            push(StateDelta(network = currentNetwork(), wifiRssi = currentRssi()))
        }

        override fun onLost(network: Network) {
            push(StateDelta(network = currentNetwork()))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            push(StateDelta(network = currentNetwork(), wifiRssi = currentRssi()))
        }
    }
}
