package app.ottermsg.core.ui.components

/** One coloured-initials pair, raw ARGB so it is testable on the JVM. */
data class AvatarColors(val background: Long, val text: Long)

/**
 * Coloured initials for people and non-family groups (D34). Warm/mint Otter family; every pair ≥ 4.5:1 (AvatarPaletteTest).
 * The colour comes from the stable id ([seed] = user id / group id), so renaming never changes it.
 */
object AvatarPalette {
    val Light = listOf(
        AvatarColors(0xFFDCF1E3, 0xFF17382D), // mint
        AvatarColors(0xFFD2EEEA, 0xFF00463E), // teal
        AvatarColors(0xFFE4EBD5, 0xFF3A4A1E), // sage
        AvatarColors(0xFFF3E7CF, 0xFF5A4100), // sand
        AvatarColors(0xFFF9E0D2, 0xFF6B2F12), // peach
        AvatarColors(0xFFF6DDE2, 0xFF6A2335), // rose
        AvatarColors(0xFFE6E0F3, 0xFF3F2F6B), // lavender
        AvatarColors(0xFFD9E8F5, 0xFF1B3F5E), // sky
    )
    val Dark = listOf(
        AvatarColors(0xFF25463A, 0xFFE0F4E7),
        AvatarColors(0xFF1F4A44, 0xFFCDEFE9),
        AvatarColors(0xFF39452A, 0xFFE3EDCF),
        AvatarColors(0xFF4A3D1E, 0xFFF5E6C4),
        AvatarColors(0xFF512F22, 0xFFFADBCB),
        AvatarColors(0xFF4D2830, 0xFFF8D8DF),
        AvatarColors(0xFF3A3352, 0xFFE5DEF8),
        AvatarColors(0xFF213B50, 0xFFD5E6F5),
    )

    /** Index from the first id byte; an empty seed (unknown id) uses the first pair. */
    fun indexFor(seed: ByteArray): Int = if (seed.isEmpty()) 0 else (seed[0].toInt() and 0xFF) % Light.size

    fun colorsFor(seed: ByteArray, dark: Boolean): AvatarColors = (if (dark) Dark else Light)[indexFor(seed)]
}
