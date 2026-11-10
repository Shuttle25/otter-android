package app.ottermsg.core.ui.theme

/** Guide §3 (normative). Raw ARGB longs so the palette is testable on the JVM without Compose. */
data class OtterPalette(
    val background: Long, val surface: Long, val surfaceVariant: Long, val onSurface: Long, val onSurfaceVariant: Long,
    val primary: Long, val onPrimary: Long, val outgoingContainer: Long, val onOutgoingContainer: Long,
    val outline: Long, val divider: Long, val error: Long, val errorContainer: Long, val warning: Long, val warningContainer: Long,
) {
    companion object {
        val Light = OtterPalette(
            background = 0xFFFAF7F2, surface = 0xFFFFFFFF, surfaceVariant = 0xFFECEFEA, onSurface = 0xFF202724,
            onSurfaceVariant = 0xFF5D6963, primary = 0xFF006B60, onPrimary = 0xFFFFFFFF, outgoingContainer = 0xFFDCF1E3,
            onOutgoingContainer = 0xFF17382D, outline = 0xFF738079, divider = 0xFFDFE4DE, error = 0xFFB3261E,
            errorContainer = 0xFFFCEAE7, warning = 0xFF775800, warningContainer = 0xFFFFF2CC,
        )
        val Dark = OtterPalette(
            background = 0xFF151B19, surface = 0xFF202925, surfaceVariant = 0xFF2A3530, onSurface = 0xFFF0F4F1,
            onSurfaceVariant = 0xFFB8C5BD, primary = 0xFF83D5C4, onPrimary = 0xFF00382F, outgoingContainer = 0xFF25463A,
            onOutgoingContainer = 0xFFE0F4E7, outline = 0xFF89988F, divider = 0xFF39463F, error = 0xFFFFB4AB,
            errorContainer = 0xFF4B2522, warning = 0xFFE7C86B, warningContainer = 0xFF40371C,
        )
    }
}
