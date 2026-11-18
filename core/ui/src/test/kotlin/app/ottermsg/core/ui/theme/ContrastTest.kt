package app.ottermsg.core.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ContrastTest {
    private fun lum(argb: Long): Double {
        fun ch(v: Long): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }
    private fun ratio(a: Long, b: Long): Double { val (x, y) = listOf(lum(a), lum(b)).sortedDescending(); return (x + 0.05) / (y + 0.05) }

    @Test fun textPairsMeetAA() {
        for (t in listOf(OtterPalette.Light, OtterPalette.Dark)) {
            val pairs = mapOf(
                "onSurface/surface" to (t.onSurface to t.surface),
                "onSurface/background" to (t.onSurface to t.background),
                "onSurfaceVariant/surface" to (t.onSurfaceVariant to t.surface),
                "onPrimary/primary" to (t.onPrimary to t.primary),
                "onOutgoing/outgoing" to (t.onOutgoingContainer to t.outgoingContainer),
                "error/errorContainer" to (t.error to t.errorContainer),
                "warning/warningContainer" to (t.warning to t.warningContainer),
            )
            for ((name, p) in pairs) { val r = ratio(p.first, p.second); assertTrue("$name = $r", r >= 4.5) }
        }
    }

    @Test fun ticksAndReadBadgeAreVisible() {
        for (t in listOf(OtterPalette.Light, OtterPalette.Dark)) {
            assertTrue("ink/outgoing", ratio(t.onOutgoingContainer, t.outgoingContainer) >= 4.5)
            assertTrue("ink/surface", ratio(t.onSurface, t.surface) >= 4.5)
            assertTrue("badge/outgoing", ratio(t.primary, t.outgoingContainer) >= 3.0)
            assertTrue("badge/surface", ratio(t.primary, t.surface) >= 3.0)
            assertTrue("badge ticks", ratio(t.onPrimary, t.primary) >= 3.0)
        }
        // the photo badge uses the dark pair on a ~45 % black scrim in both themes
        assertTrue("photo badge ticks", ratio(OtterPalette.Dark.onPrimary, OtterPalette.Dark.primary) >= 3.0)
    }
}
