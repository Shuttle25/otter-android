package app.ottermsg.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Current network type and mobile operator name for the connectivity log (D74; no extra permission needed). */
@Singleton
open class CurrentNetwork @Inject constructor(@ApplicationContext private val ctx: Context) {
    /** ("wifi" | "mobile" | "other" | "none", operator name for mobile else ""). */
    open fun current(): Pair<String, String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching NONE
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi" to ""
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "mobile" to (ctx.getSystemService(TelephonyManager::class.java)?.networkOperatorName ?: "")
            else -> "other" to ""
        }
    }.getOrDefault(NONE)

    private companion object { val NONE = "none" to "" }
}
