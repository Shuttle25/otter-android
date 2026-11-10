package app.ottermsg.core.network

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConnectionManagerTest {
    @Test fun failsOverAndReconnects() = runTest {
        val t = FakeTransport(failUrls = setOf("bad")) { ok() }
        var logins = 0
        val cm = ConnectionManager(t, { listOf("bad", "good") }, backgroundScope, { logins++ }, { currentTime }, Random(1))
        cm.start()
        val online = cm.state.first { it is ConnectionState.Online } as ConnectionState.Online
        assertEquals("good", online.url)
        assertEquals(1, logins)
        (t.connections.last()).drop()
        cm.state.first { it is ConnectionState.Waiting }
        advanceTimeBy(2_000)
        cm.state.first { it is ConnectionState.Online }
        assertEquals(2, logins)
        cm.stop()
        assertTrue(cm.state.value is ConnectionState.Offline)
    }

    @Test fun backoffWhenAllFail() = runTest {
        val t = FakeTransport(failUrls = setOf("a")) { ok() }
        val cm = ConnectionManager(t, { listOf("a") }, backgroundScope, {}, { currentTime }, Random(1))
        cm.start()
        val w1 = cm.state.first { it is ConnectionState.Waiting } as ConnectionState.Waiting
        assertTrue(w1.retryAtMs - currentTime in 800..1200)
        advanceTimeBy(1_300)
        val w2 = cm.state.first { it is ConnectionState.Waiting && it.retryAtMs > w1.retryAtMs } as ConnectionState.Waiting
        assertTrue(w2.retryAtMs - currentTime in 1600..2400)
        cm.stop()
    }
}
