package app.ottermsg.core.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AnchoredMenuPositionTest {
    private val window = IntSize(1080, 2400)
    private val popup = IntSize(400, 300)
    private val gap = 24

    @Test fun outgoingNearBottomOpensAboveRightAligned() {
        val bubble = IntRect(left = 600, top = 2000, right = 1050, bottom = 2150)
        assertEquals(IntOffset(1050 - 400, 2000 - 24 - 300), anchoredMenuOffset(bubble, window, popup, alignEnd = true, gapPx = gap))
    }

    @Test fun incomingAlignsToStart() {
        val bubble = IntRect(left = 30, top = 1200, right = 500, bottom = 1300)
        assertEquals(IntOffset(30, 1200 - 24 - 300), anchoredMenuOffset(bubble, window, popup, alignEnd = false, gapPx = gap))
    }

    @Test fun bubbleAtTopOpensBelow() {
        val bubble = IntRect(left = 30, top = 100, right = 500, bottom = 250)
        assertEquals(IntOffset(30, 250 + 24), anchoredMenuOffset(bubble, window, popup, alignEnd = false, gapPx = gap))
    }

    @Test fun tallBubbleGoesToTop() {
        val bubble = IntRect(left = 30, top = 200, right = 500, bottom = 2300)
        assertEquals(IntOffset(30, 0), anchoredMenuOffset(bubble, window, popup, alignEnd = false, gapPx = gap))
    }

    @Test fun narrowWindowClampsX() {
        val narrow = IntSize(350, 2400)
        val bubble = IntRect(left = 10, top = 1200, right = 340, bottom = 1300)
        assertEquals(0, anchoredMenuOffset(bubble, narrow, popup, alignEnd = true, gapPx = gap).x)
        val right = IntRect(left = 900, top = 1200, right = 1080, bottom = 1300)
        assertEquals(1080 - 400, anchoredMenuOffset(right, window, popup, alignEnd = false, gapPx = gap).x)
    }
}
