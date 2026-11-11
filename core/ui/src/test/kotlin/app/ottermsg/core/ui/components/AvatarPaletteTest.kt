package app.ottermsg.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AvatarPaletteTest {
    private fun lum(argb: Long): Double {
        fun ch(v: Long): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }
    private fun ratio(a: Long, b: Long): Double { val (x, y) = listOf(lum(a), lum(b)).sortedDescending(); return (x + 0.05) / (y + 0.05) }

    @Test fun everyPairMeetsAA() {
        assertEquals(8, AvatarPalette.Light.size)
        assertEquals(8, AvatarPalette.Dark.size)
        (AvatarPalette.Light + AvatarPalette.Dark).forEachIndexed { i, c ->
            val r = ratio(c.text, c.background)
            assertTrue("pair $i = $r", r >= 4.5)
        }
    }

    @Test fun colourIsStableForTheId() {
        val id = byteArrayOf(0x0B, 1, 2, 3)
        assertEquals(3, AvatarPalette.indexFor(id))
        assertEquals(AvatarPalette.indexFor(id), AvatarPalette.indexFor(id.copyOf()))
        assertEquals(0, AvatarPalette.indexFor(ByteArray(0)))
        assertEquals(255 % 8, AvatarPalette.indexFor(byteArrayOf(-1)))
    }
}
