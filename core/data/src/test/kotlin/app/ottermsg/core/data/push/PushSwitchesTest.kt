package app.ottermsg.core.data.push

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.data.invite.OnlineApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushSwitchesTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    @After fun tearDown() = g.close()

    private class FakeTokens(var next: String? = "tok-1") : PushTokenSource {
        var deleted = 0
        override suspend fun fetch(): String? = next
        override suspend fun delete() { deleted++ }
    }

    private suspend fun setup(): Pair<PushSwitches, FakeTokens> {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val api = node.connect().also { it.login(g.me.userId, g.me.accountKey) }
        val src = FakeTokens()
        return PushSwitches(g.settings, PushRegistrar(g.accounts, g.settings, g.clock), OnlineApi { api }, src) to src
    }

    private fun record() = node.pushRecords.values.single()

    @Test fun notificationsOffMeansCallsOnly() = runBlocking {
        val (sw, _) = setup()
        g.settings.setPushAvailable(true)
        sw.setGoogleWake(true) // first start: fetch + register
        assertEquals("tok-1", record().token); assertFalse(record().messagesOff)
        sw.setNotifications(false)
        assertEquals("tok-1", record().token); assertTrue(record().messagesOff)
        assertTrue(node.hasPush(g.me.userId)) // calls still wake the phone
        sw.setNotifications(true)
        assertFalse(record().messagesOff)
    }

    @Test fun googleWakeOffWithdrawsTheToken() = runBlocking {
        val (sw, src) = setup()
        g.settings.setPushAvailable(true)
        sw.setGoogleWake(true)
        sw.setNotifications(false)
        sw.setGoogleWake(false)
        assertTrue(record().withdrawn); assertEquals("", record().token)
        assertEquals(1, src.deleted)
        assertFalse(node.hasPush(g.me.userId))
        assertEquals(PushMode.SAVER, g.pushMonitor.mode())
        src.next = "tok-2"
        sw.setGoogleWake(true)
        assertFalse(record().withdrawn); assertEquals("tok-2", record().token); assertTrue(record().messagesOff)
    }

    @Test fun noWithdrawalWhenNothingWasEverRegistered() = runBlocking {
        val (sw, _) = setup()
        sw.setGoogleWake(false)
        assertTrue(node.pushRecords.isEmpty())
    }
}
