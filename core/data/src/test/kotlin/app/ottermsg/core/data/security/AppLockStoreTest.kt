package app.ottermsg.core.data.security

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app lock over the real settings table: what survives a restart, and what is (not) written. */
@RunWith(RobolectricTestRunner::class)
class AppLockStoreTest {
    private lateinit var db: OtterDatabase
    private var now = 5_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    /** A fresh process: new Settings, store and AppLock over the same database. */
    private fun startApp() = AppLock(SettingsAppLockStore(Settings(db.settingsDao())), { now }, Argon2PinHasher)

    private fun allSettings(): List<Pair<String, ByteArray>> =
        db.query(SimpleSQLiteQuery("SELECT `key`, value FROM settings")).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getBlob(1)) }
        }

    @Test fun defaultsAreOff() = runTest {
        val s = Settings(db.settingsDao())
        val l = startApp(); l.init()
        assertFalse(l.enabled.value); assertFalse(l.locked.value)
        assertEquals(60_000L, s.appLockTimeoutMs())
        assertEquals(0, s.appLockFailures()); assertEquals(0L, s.appLockUntilMs())
        assertFalse(s.appLockBiometric()); assertEquals(null, s.appLockHash())
        assertFalse(s.observeAppLockOn().first())
        assertFalse(s.observeScreenSecure().first())
        assertEquals(0, s.observeThemeMode().first())
    }

    @Test fun wrongPinBackoffSurvivesARestart() = runTest {
        startApp().apply { init(); enable("4711".toCharArray(), biometric = false) }
        val first = startApp().apply { init() }
        repeat(4) { assertEquals(PinResult.Wrong(4 - it), first.tryPin("0000".toCharArray())) }
        assertEquals(PinResult.Wait(now + 30_000), first.tryPin("0000".toCharArray()))

        // The attacker kills the app and starts it again: still locked, still waiting, even with the right PIN.
        val second = startApp().apply { init() }
        assertTrue(second.locked.value)
        assertEquals(PinResult.Wait(now + 30_000), second.tryPin("4711".toCharArray()))
        now += 30_000
        val third = startApp().apply { init() }
        assertEquals("the count goes on after the restart", PinResult.Wait(now + 60_000), third.tryPin("0000".toCharArray()))
        now += 60_000
        assertEquals(PinResult.Ok, startApp().apply { init() }.tryPin("4711".toCharArray()))
        assertEquals(0, Settings(db.settingsDao()).appLockFailures())
    }

    @Test fun storedHashNeverContainsThePin() = runTest {
        val pin = "86429753"
        val l = startApp(); l.init()
        l.enable(pin.toCharArray(), biometric = true)
        l.tryPin("11111111".toCharArray())
        l.tryPin(pin.toCharArray())
        val rows = allSettings()
        assertTrue(rows.any { it.first == "app_lock_hash" })
        for ((key, value) in rows) for (enc in listOf(Charsets.UTF_8, Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val needle = pin.toByteArray(enc).copyOf(4 * (if (enc == Charsets.UTF_8) 1 else 2))
            assertFalse("$key holds the PIN", containsRun(value, needle))
            assertFalse("$key holds the wrong PIN", containsRun(value, "1111".toByteArray(enc)))
        }
        assertEquals(32 + 16, Settings(db.settingsDao()).appLockHash()!!.size)
    }

    @Test fun disableDeletesTheHashRow() = runTest {
        val s = Settings(db.settingsDao())
        val l = startApp(); l.init()
        l.enable("1234".toCharArray(), biometric = true)
        assertTrue(s.observeAppLockOn().first())
        l.setTimeoutMs(0)
        l.disable()
        assertFalse(allSettings().any { it.first == "app_lock_hash" })
        assertFalse(s.observeAppLockOn().first())
        assertFalse(s.appLockBiometric())
        val again = startApp(); again.init()
        assertFalse(again.enabled.value); assertFalse(again.locked.value)
    }

    @Test fun screenSecureAndTheme() = runTest {
        val s = Settings(db.settingsDao())
        s.setScreenSecure(true); s.setThemeMode(2)
        assertTrue(s.observeScreenSecure().first()); assertEquals(2, s.observeThemeMode().first())
    }

    private fun containsRun(hay: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
}
