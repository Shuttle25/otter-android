package app.ottermsg.core.data.push

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.data.eventually
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundSyncTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val alice = TestGraph(node, clock)
    private val bob = TestGraph(node, clock)
    /** TestClock only ticks per call; BackgroundSync's idle wait needs it to follow real time. */
    private val ticker = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply {
        launch { while (true) { delay(10); clock.advance(10) } }
    }

    @After fun tearDown() { ticker.cancel(); alice.close(); bob.close() }

    private suspend fun family() {
        alice.register(node.bootstrapLink(), "Алиса", "alice").getOrThrow()
        alice.online()
        bob.register(alice.invites.create().link, "Боб", "bob").getOrThrow()
        eventually(what = "alice knows bob") { alice.db.contactDao().get(bob.me.userId) != null }
    }

    @Test fun drainsAndGoesOffline() = runBlocking {
        family()
        // bob's engine is not running: the message waits in his mailbox
        alice.messages.sendText(alice.messages.conversationWith(bob.me.userId), "hi")
        eventually(what = "queued for bob") { node.mailbox(bob.me.userId).isNotEmpty() }

        val bg = BackgroundSync(bob.engine, bob.pushMonitor, bob.clock)
        assertTrue(bg.drain(fromPush = true, maxMs = 20_000, idleMs = 500))
        assertEquals("hi", bob.db.messageDao().all().single { !it.outgoing }.body)
        assertTrue(bob.engine.connection.value is ConnectionState.Offline)
        assertTrue(!bob.engine.holds(SyncEngine.WAKE))
        assertTrue(bob.settings.pushLastWakeMs() > 0)
        eventually(what = "offline time recorded") { bob.settings.lastOfflineMs() > 0 }
        eventually(what = "acked") { node.mailbox(bob.me.userId).isEmpty() }
    }

    @Test fun visibleAppKeepsRunning() = runBlocking {
        family()
        bob.foreground.foreground()
        val bg = BackgroundSync(bob.engine, bob.pushMonitor, bob.clock)
        assertTrue(bg.drain(fromPush = false, maxMs = 20_000, idleMs = 500))
        assertTrue(bob.engine.holds(SyncEngine.FOREGROUND))
        assertTrue(bob.engine.connection.value is ConnectionState.Online)
        assertEquals(0L, bob.settings.pushLastWakeMs())
    }

    @Test fun registersThePushTokenAtLogin() = runBlocking {
        family()
        bob.settings.setPushToken("tok-bob")
        assertTrue(BackgroundSync(bob.engine, bob.pushMonitor, bob.clock).drain(fromPush = true, maxMs = 20_000, idleMs = 500))
        eventually(what = "token registered") { node.pushTokens[bob.me.userId.joinToString("") { "%02x".format(it) }] == "tok-bob" }
        assertEquals("tok-bob", bob.settings.pushTokenSent())
    }
}
