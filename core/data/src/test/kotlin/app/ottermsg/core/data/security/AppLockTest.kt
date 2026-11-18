package app.ottermsg.core.data.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeLockStore : AppLockStore {
    var hash: ByteArray? = null; var bio = false; var timeout = AppLock.DEFAULT_TIMEOUT_MS; var fails = 0; var until = 0L
    override suspend fun pinHash() = hash
    override suspend fun setPinHash(v: ByteArray?) { hash = v }
    override suspend fun biometric() = bio
    override suspend fun setBiometric(v: Boolean) { bio = v }
    override suspend fun timeoutMs() = timeout
    override suspend fun setTimeoutMs(v: Long) { timeout = v }
    override suspend fun failures() = fails
    override suspend fun setFailures(v: Int) { fails = v }
    override suspend fun lockedUntilMs() = until
    override suspend fun setLockedUntilMs(v: Long) { until = v }
}

/** Plain-text "hash" so the state machine tests run fast; PinHash has its own test. */
object PlainHasher : PinHasher {
    override fun hash(pin: CharArray) = String(pin).encodeToByteArray()
    override fun verify(pin: CharArray, stored: ByteArray) = String(pin).encodeToByteArray().contentEquals(stored)
}

class AppLockTest {
    private var now = 1_000_000L
    private val store = FakeLockStore()
    private fun lock(hasher: PinHasher = PlainHasher) = AppLock(store, { now }, hasher)

    @Test fun offUntilTurnedOn() = runTest {
        val l = lock(); l.init()
        assertTrue(l.initialized.value)
        assertFalse(l.enabled.value); assertFalse(l.locked.value)
        l.onBackground(); now += 3_600_000; l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test fun locksAfterTimeoutOnly() = runTest {
        val l = lock(); l.init()
        assertFalse("not initialized before init()", lock().initialized.value)
        l.enable("1234".toCharArray(), biometric = false)
        assertFalse(l.locked.value)
        l.onBackground(); now += 59_000; l.onForeground()
        assertFalse("before the timeout", l.locked.value)
        l.onBackground(); now += 60_000; l.onForeground()
        assertTrue("after the timeout", l.locked.value)
        val fresh = lock(); fresh.init()
        assertTrue("locked at start when enabled", fresh.locked.value)
        assertTrue(fresh.enabled.value)
    }

    @Test fun timeoutZeroLocksOnEveryReturn() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        l.setTimeoutMs(0)
        l.onBackground()
        assertTrue("locked as soon as the app leaves the screen", l.locked.value)
        assertEquals(PinResult.Ok, l.tryPin("1234".toCharArray()))
        l.onBackground(); l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test fun clockGoingBackDoesNotKeepItOpen() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        l.onBackground(); now -= 3_600_000; l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test fun timeoutChoices() = runTest {
        val l = lock(); l.init()
        assertEquals(AppLock.DEFAULT_TIMEOUT_MS, l.timeoutMs())
        for (t in AppLock.TIMEOUTS_MS) { l.setTimeoutMs(t); assertEquals(t, store.timeout) }
        try { l.setTimeoutMs(7); fail("only the offered choices") } catch (_: IllegalArgumentException) {}
    }

    @Test fun pinMustBeFourToEightDigits() = runTest {
        val l = lock(); l.init()
        for (bad in listOf("123", "123456789", "12a4", "", "١٢٣٤")) {
            try { l.enable(bad.toCharArray(), false); fail(bad) } catch (_: IllegalArgumentException) {}
        }
        assertFalse(l.enabled.value); assertEquals(null, store.hash)
        assertTrue(AppLock.validPin("1234".toCharArray())); assertTrue(AppLock.validPin("12345678".toCharArray()))
    }

    @Test fun wrongPinBackoffPersists() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        repeat(4) { assertEquals(PinResult.Wrong(4 - it), l.tryPin("0000".toCharArray())) }
        assertEquals(PinResult.Wait(now + 30_000), l.tryPin("0000".toCharArray()))
        assertEquals("right PIN during the wait", PinResult.Wait(now + 30_000), lock().tryPin("1234".toCharArray()))
        assertEquals(5, store.fails)
        now += 30_000
        assertEquals(PinResult.Wait(now + 60_000), l.tryPin("0000".toCharArray()))
        now += 60_000
        assertEquals(PinResult.Wait(now + 120_000), l.tryPin("0000".toCharArray()))
        now += 120_000
        assertEquals(PinResult.Ok, l.tryPin("1234".toCharArray()))
        assertEquals(0, store.fails); assertEquals(0L, store.until)
        store.fails = 40; store.until = 0 // a long series of wrong guesses: the wait is capped
        assertEquals(PinResult.Wait(now + AppLock.MAX_WAIT_MS), l.tryPin("0000".toCharArray()))
    }

    @Test fun restartDoesNotResetTheBackoff() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        repeat(5) { l.tryPin("0000".toCharArray()) }
        // The app is killed and started again: the wait and the failure count come from the store.
        val after = lock(); after.init()
        assertTrue(after.locked.value)
        assertEquals(PinResult.Wait(now + 30_000), after.tryPin("1234".toCharArray()))
        now += 30_000
        assertEquals("the 6th wrong PIN doubles, not restarts", PinResult.Wait(now + 60_000), after.tryPin("0000".toCharArray()))
    }

    @Test fun killDuringTheCheckStillCountsTheTry() = runTest {
        // An attacker kills the app while the (slow) hash runs, hoping the failure is never written.
        val dies = object : PinHasher {
            override fun hash(pin: CharArray) = PlainHasher.hash(pin)
            override fun verify(pin: CharArray, stored: ByteArray): Boolean = throw IllegalStateException("killed")
        }
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        val dying = lock(dies)
        repeat(5) { try { dying.tryPin("0000".toCharArray()) } catch (_: IllegalStateException) {} }
        assertEquals(5, store.fails)
        assertEquals(PinResult.Wait(now + 30_000), lock().tryPin("1234".toCharArray()))
    }

    @Test fun clockSetBackDoesNotLockForever() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        store.fails = 5; store.until = now + 365L * 86_400_000 // e.g. the clock was wrong (far ahead) at the 5th try
        assertEquals(PinResult.Wait(now + AppLock.MAX_WAIT_MS), l.tryPin("1234".toCharArray()))
        now += AppLock.MAX_WAIT_MS
        assertEquals(PinResult.Ok, l.tryPin("1234".toCharArray()))
    }

    @Test fun deviceUnlock() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = true)
        l.lockNow()
        assertTrue(l.locked.value)
        l.unlockedByDevice()
        assertFalse(l.locked.value)
        assertTrue(l.biometric())
    }

    @Test fun disableUnlocksAndForgetsPin() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = true)
        l.onBackground(); now += 3_600_000; l.onForeground()
        store.fails = 3
        l.disable()
        assertFalse(l.locked.value); assertFalse(l.enabled.value)
        assertEquals(null, store.hash); assertFalse(store.bio); assertEquals(0, store.fails)
        assertTrue(l.checkPin("1234".toCharArray()) == PinResult.Ok) // off: nothing to check
    }

    @Test fun changePinNeedsTheOldOne() = runTest {
        val l = lock(); l.init()
        l.enable("1234".toCharArray(), biometric = false)
        assertEquals(PinResult.Wrong(4), l.changePin("9999".toCharArray(), "5678".toCharArray()))
        assertEquals(PinResult.Ok, l.changePin("1234".toCharArray(), "5678".toCharArray()))
        assertEquals(PinResult.Ok, l.tryPin("5678".toCharArray()))
        assertEquals(PinResult.Wrong(4), l.tryPin("1234".toCharArray()))
    }
}
