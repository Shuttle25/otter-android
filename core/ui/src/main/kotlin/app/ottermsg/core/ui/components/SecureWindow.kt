package app.ottermsg.core.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** Screens per window that asked for FLAG_SECURE; main thread only. */
private val secureScreens = WeakHashMap<Window, Int>()

/**
 * FLAG_SECURE while the recovery words or a backup password are on screen (C22, D98): no screenshots, hidden in the
 * recents preview. Reference-counted, so the next secure screen keeps the flag when the previous one leaves.
 */
@Composable
fun SecureWindow() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        if (window != null) {
            val n = secureScreens[window] ?: 0
            secureScreens[window] = n + 1
            if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null) {
                val n = (secureScreens[window] ?: 1) - 1
                if (n <= 0) { secureScreens.remove(window); window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                else secureScreens[window] = n
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
