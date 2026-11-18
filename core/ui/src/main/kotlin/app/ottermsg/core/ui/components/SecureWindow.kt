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
 * FLAG_SECURE while the recovery words or a backup password are on screen (C22, D98), and app-wide while
 * «Запрет скриншотов» is on (D121, [enabled]): no screenshots, hidden in the recents preview. Reference-counted per
 * window, so the flag stays until the last holder leaves: leaving a secret screen does not clear the app-wide setting,
 * and switching the setting off does not unprotect a secret screen that is open.
 */
@Composable
fun SecureWindow(enabled: Boolean = true) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window, enabled) {
        val held = window?.takeIf { enabled }
        if (held != null) {
            val n = secureScreens[held] ?: 0
            secureScreens[held] = n + 1
            if (n == 0) held.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (held != null) {
                val n = (secureScreens[held] ?: 1) - 1
                if (n <= 0) { secureScreens.remove(held); held.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                else secureScreens[held] = n
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
