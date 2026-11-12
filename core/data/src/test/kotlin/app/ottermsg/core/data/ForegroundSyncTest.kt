package app.ottermsg.core.data

import app.ottermsg.core.data.push.AppVisibility
import app.ottermsg.core.data.sync.ForegroundSync
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSyncTest {
    @Test fun stopsTenSecondsAfterBackgroundUnlessReturning() = runTest {
        var starts = 0; var stops = 0
        val visibility = AppVisibility()
        val fs = ForegroundSync({ starts++ }, { stops++ }, backgroundScope, {}, visibility)
        fs.foreground()
        assertTrue(visibility.visible)
        fs.background()
        assertFalse(visibility.visible)
        advanceTimeBy(9_000); runCurrent()
        fs.foreground() // came back in time: no stop
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, stops)
        fs.background()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(1, stops)
        assertEquals(2, starts)
    }

    /** A fake engine: foreground() retries at once only while the connection is waiting out a backoff. */
    @Test fun foregroundRetriesWhenWaiting() = runTest {
        var state: app.ottermsg.core.network.ConnectionState = app.ottermsg.core.network.ConnectionState.Waiting(Long.MAX_VALUE, "backoff")
        var retries = 0
        val fs = ForegroundSync({}, {}, backgroundScope, { if (state is app.ottermsg.core.network.ConnectionState.Waiting) retries++ })
        fs.foreground()
        assertEquals(1, retries)
        state = app.ottermsg.core.network.ConnectionState.Connecting
        fs.background(); fs.foreground()
        assertEquals(1, retries)
    }
}
