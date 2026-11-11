package app.ottermsg.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reports default-network switches (Wi-Fi ↔ mobile) while started by ForegroundSync. Needs ACCESS_NETWORK_STATE (core:data manifest). */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var current: Network? = null

    @Synchronized fun start(onChange: () -> Unit) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Never crash the app over this: without network info we just lose the fast reconnect on network switches.
        current = runCatching { cm.activeNetwork }.getOrNull()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = synchronized(this@NetworkMonitor) { (network != current).also { current = network } }
                if (changed) onChange()
            }
            override fun onLost(network: Network) { synchronized(this@NetworkMonitor) { if (network == current) current = null } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    @Synchronized fun stop() {
        val cb = callback ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        callback = null
    }
}
