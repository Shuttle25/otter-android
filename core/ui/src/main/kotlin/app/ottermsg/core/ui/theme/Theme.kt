package app.ottermsg.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class OtterExtraColors(
    val outgoingContainer: Color, val onOutgoingContainer: Color, val divider: Color,
    val warning: Color, val warningContainer: Color,
)

private val LocalExtra = staticCompositionLocalOf { extra(OtterPalette.Light) }

private fun extra(p: OtterPalette) = OtterExtraColors(Color(p.outgoingContainer), Color(p.onOutgoingContainer),
    Color(p.divider), Color(p.warning), Color(p.warningContainer))

private fun scheme(p: OtterPalette, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    background = Color(p.background), onBackground = Color(p.onSurface),
    surface = Color(p.surface), onSurface = Color(p.onSurface),
    surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant),
    primary = Color(p.primary), onPrimary = Color(p.onPrimary),
    outline = Color(p.outline), outlineVariant = Color(p.divider),
    error = Color(p.error), errorContainer = Color(p.errorContainer), onErrorContainer = Color(p.error),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Otter theme. No dynamic color (guide §3): light and dark keep Otter's own palette. */
@Composable
fun OtterTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val p = if (dark) OtterPalette.Dark else OtterPalette.Light
    CompositionLocalProvider(LocalExtra provides extra(p)) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = OtterTypography, content = content)
    }
}

object OtterTheme {
    val extraColors: OtterExtraColors @Composable get() = LocalExtra.current
}
