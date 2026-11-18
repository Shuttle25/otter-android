package app.ottermsg

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import app.ottermsg.core.ui.theme.ThemeMode

// enableEdgeToEdge()'s own navigation-bar scrims (used where the bar cannot be transparent, e.g. 3-button navigation).
private val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Edge-to-edge with status/navigation bar icons that match «Оформление» (D124): as the device follows the system's
 * night mode; a forced light or dark theme gets dark or light icons whatever the system says. [themeMode] is the stored
 * ThemeMode ordinal; unknown values act as SYSTEM. Safe to call again whenever the choice changes.
 */
fun ComponentActivity.applySystemBars(themeMode: Int) = when (ThemeMode.entries.getOrElse(themeMode) { ThemeMode.SYSTEM }) {
    ThemeMode.SYSTEM -> enableEdgeToEdge()
    ThemeMode.LIGHT -> enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(LightScrim, DarkScrim))
    ThemeMode.DARK -> enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(DarkScrim))
}
