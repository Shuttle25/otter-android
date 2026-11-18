package app.ottermsg

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D124: with a forced light/dark theme the status and navigation bar icons follow the chosen theme, not the system's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // plain Application: OtterApp would open the real (SQLCipher) graph
class SystemBarsTest {
    /** (light status-bar icons?, light navigation-bar icons?) — "light appearance" means dark icons for a light background. */
    private fun barsAfter(mode: Int): Pair<Boolean, Boolean> {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        a.applySystemBars(mode)
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        return c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars
    }

    @Config(qualifiers = "night")
    @Test fun forcedLightOnADarkPhone() {
        assertEquals(true to true, barsAfter(1))
        assertEquals(false to false, barsAfter(0)) // as the device: dark
        assertEquals(false to false, barsAfter(2))
    }

    @Config(qualifiers = "notnight")
    @Test fun forcedDarkOnALightPhone() {
        assertEquals(false to false, barsAfter(2))
        assertEquals(true to true, barsAfter(0)) // as the device: light
        assertEquals(true to true, barsAfter(1))
    }

    @Test fun unknownStoredValueActsAsSystem() {
        assertEquals(barsAfter(0), barsAfter(7))
    }

    @Test fun switchingBackAndForthOnOneActivity() {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        a.applySystemBars(2); assertEquals(false, c.isAppearanceLightStatusBars)
        a.applySystemBars(1); assertEquals(true, c.isAppearanceLightStatusBars)
        a.applySystemBars(2); assertEquals(false, c.isAppearanceLightStatusBars)
    }
}
