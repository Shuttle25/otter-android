package app.ottermsg.core.data.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Is the current network unmetered (Wi-Fi-like)? Decides "Wi-Fi only" auto-download (D47). */
fun interface NetworkInfo { fun unmetered(): Boolean }

@Singleton
class AndroidNetworkInfo @Inject constructor(@ApplicationContext private val context: Context) : NetworkInfo {
    override fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)
}
