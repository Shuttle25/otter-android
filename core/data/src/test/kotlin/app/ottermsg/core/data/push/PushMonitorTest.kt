package app.ottermsg.core.data.push

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushMonitorTest {
    private lateinit var db: OtterDatabase
    private lateinit var settings: Settings
    private var now = 10 * 86_400_000L
    private lateinit var monitor: PushMonitor

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = Settings(db.settingsDao())
        monitor = PushMonitor(settings) { now }
    }

    @After fun tearDown() = db.close()

    @Test fun fastNeedsAvailabilityAndToken() = runTest {
        assertEquals(PushMode.SAVER, monitor.mode())
        settings.setPushAvailable(true)
        assertEquals(PushMode.SAVER, monitor.mode())
        settings.setPushToken("t")
        assertEquals(PushMode.FAST, monitor.mode())
    }

    @Test fun threeMissedWakeupsSwitchToSaverAndAWakeRestores() = runTest {
        settings.setPushAvailable(true); settings.setPushToken("t")
        repeat(3) { session ->
            now += 3_600_000
            settings.setLastOfflineMs(now - 600_000)            // offline for 10 min
            val start = now
            monitor.onDelivered(listOf(now - 300_000, now - 200_000), start, sessionId = session.toLong()) // waited 5 min, no wake
            monitor.onDelivered(listOf(now - 100_000), start, sessionId = session.toLong())               // same session: no 2nd miss
        }
        assertEquals(3, settings.pushMisses().size)
        assertEquals(PushMode.SAVER, monitor.mode())
        monitor.onWake()
        assertEquals(PushMode.FAST, monitor.mode())
    }

    @Test fun notAMissAndExpiry() = runTest {
        settings.setPushAvailable(true); settings.setPushToken("t")
        settings.setLastOfflineMs(now - 600_000)
        monitor.onDelivered(listOf(now - 30_000), now, sessionId = 1)        // only 30 s late
        monitor.onDelivered(listOf(now - 900_000), now, sessionId = 2)       // created before we went offline
        settings.setPushLastWakeMs(now - 400_000)
        monitor.onDelivered(listOf(now - 500_000), now, sessionId = 3)       // created before the last wake
        assertEquals(0, settings.pushMisses().size)
        repeat(3) { monitor.onDelivered(listOf(now - 300_000), now, sessionId = 10L + it) } // three real misses
        assertEquals(PushMode.SAVER, monitor.mode())
        now += 2 * 86_400_000
        assertEquals(PushMode.FAST, monitor.mode())                          // misses older than 24 h no longer count
    }
}
