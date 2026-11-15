package app.ottermsg.core.network

import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConnectionManagerTest {
    private fun manager(t: FakeTransport, cands: List<Candidate>, scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long,
                        stats: EndpointStats = InMemoryEndpointStats(), netLog: NetLog = NetLog.None,
                        onConnected: suspend (NodeApi) -> Unit = {}) =
        ConnectionManager(NodeSelector(t, stats, clock), { cands }, stats, scope, { onConnected(it) }, clock, Random(1), netLog)

    @Test fun failsOverAndReconnects() = runTest {
        val t = FakeTransport(failUrls = setOf("bad")) { ok() }
        var logins = 0
        val cm = manager(t, listOf(cand("bad"), cand("good")), backgroundScope, { currentTime }) { logins++ }
        cm.start()
        val online = cm.state.first { it is ConnectionState.Online } as ConnectionState.Online
        assertEquals("good", online.url)
        assertEquals("good", online.nodeLabel)
        assertEquals(1, logins)
        t.connections.last().drop()
        cm.state.first { it is ConnectionState.Waiting }
        advanceTimeBy(2_000)
        cm.state.first { it is ConnectionState.Online }
        assertEquals(2, logins)
        cm.stop()
        assertTrue(cm.state.value is ConnectionState.Offline)
    }

    @Test fun loginFailureMovesToNextCandidateWithoutBackoff() = runTest {
        val t = FakeTransport { ok() }
        val stats = InMemoryEndpointStats()
        var attempts = 0
        val cm = manager(t, listOf(cand("lagging"), cand("ok")), backgroundScope, { currentTime }, stats) { api ->
            attempts++
            if (attempts == 1) throw RpcException(Status.STATUS_RETRY_LATER, "unknown user")
        }
        cm.start()
        val online = cm.state.first { it is ConnectionState.Online } as ConnectionState.Online
        assertEquals("ok", online.url)
        assertTrue("no backoff wait", currentTime < 1_000)
        assertEquals(1, stats.get("lagging")!!.failures)
        cm.stop()
    }

    @Test fun backoffWhenAllFail() = runTest {
        val t = FakeTransport(failUrls = setOf("a")) { ok() }
        val cm = manager(t, listOf(cand("a")), backgroundScope, { currentTime })
        cm.start()
        val w1 = cm.state.first { it is ConnectionState.Waiting } as ConnectionState.Waiting
        assertTrue(w1.retryAtMs - currentTime in 800..1200)
        advanceTimeBy(1_300)
        val w2 = cm.state.first { it is ConnectionState.Waiting && it.retryAtMs > w1.retryAtMs } as ConnectionState.Waiting
        assertTrue(w2.retryAtMs - currentTime in 1600..2400)
        cm.stop()
    }

    @Test fun retryNowSkipsTheWait() = runTest {
        val t = FakeTransport(failUrls = setOf("a")) { ok() }
        val cm = manager(t, listOf(cand("a")), backgroundScope, { currentTime })
        cm.start()
        cm.state.first { it is ConnectionState.Waiting }
        t.failUrls = emptySet()
        val before = currentTime
        cm.retryNow()
        cm.state.first { it is ConnectionState.Online }
        assertTrue(currentTime - before < 100)
        cm.stop()
    }

    @Test fun reconnectRacesAgainImmediately() = runTest {
        val t = FakeTransport { ok() }
        var logins = 0
        val cm = manager(t, listOf(cand("x")), backgroundScope, { currentTime }) { logins++ }
        cm.start()
        cm.state.first { it is ConnectionState.Online }
        val before = currentTime
        cm.reconnect()
        cm.state.first { it is ConnectionState.Online && logins == 2 }
        assertTrue("no backoff", currentTime - before < 100)
        assertTrue(t.connections.first().closed)
        cm.stop()
    }

    @Test fun networkChangeWhileConnectingRacesAgainWithoutBackoff() = runTest {
        // Old network: connects hang 10 s and fail (LAN node unreachable over mobile data).
        val t = FakeTransport(failUrls = setOf("a"), delays = mapOf("a" to 10_000)) { ok() }
        val cm = manager(t, listOf(cand("a")), backgroundScope, { currentTime })
        cm.start()
        repeat(4) { advanceTimeBy(20_000) } // backoff grown well past 1 s
        cm.state.first { it is ConnectionState.Connecting }
        t.failUrls = emptySet(); t.delays = emptyMap() // Wi-Fi is back
        val before = currentTime
        cm.networkChanged()
        cm.state.first { it is ConnectionState.Online }
        assertTrue("only the in-flight attempt is waited out, no backoff", currentTime - before <= 10_000)
        cm.stop()
    }

    @Test fun networkChangeResetsBackoff() = runTest {
        val t = FakeTransport(failUrls = setOf("a")) { ok() }
        val cm = manager(t, listOf(cand("a")), backgroundScope, { currentTime })
        cm.start()
        repeat(6) { advanceTimeBy(70_000) }
        cm.state.first { it is ConnectionState.Waiting }
        cm.networkChanged() // still unreachable on the new network
        val w = cm.state.first { it is ConnectionState.Waiting && it.retryAtMs - currentTime < 2_000 } as ConnectionState.Waiting
        assertTrue(w.retryAtMs - currentTime in 800..1200)
        cm.stop()
    }

    @Test fun resetAfterKbCountsAsThrottle() = runTest {
        val t = FakeTransport { ok() }
        val stats = InMemoryEndpointStats()
        val log = RecordingNetLog()
        val cm = manager(t, listOf(cand("wss://ru:443/p")), backgroundScope, { currentTime }, stats, log)
        cm.start()
        cm.state.first { it is ConnectionState.Online }
        advanceTimeBy(1_000)
        t.connections.last().apply { bytesIn = 4096 }.drop(java.io.IOException("reset by peer"))
        cm.state.first { it is ConnectionState.Waiting }
        assertEquals(1, stats.get("wss://ru:443/p")!!.throttleCount)
        val e = log.entries.single { it.result == FailureClass.RESET_AFTER_KB }
        assertEquals("wss://ru:443", e.endpoint)
        assertTrue(e.detail, e.detail.startsWith("4096 bytes in 1 s"))
        cm.stop()
    }

    @Test fun smallOrCleanSessionEndsAreNotThrottles() = runTest {
        val t = FakeTransport { ok() }
        val stats = InMemoryEndpointStats()
        val log = RecordingNetLog()
        val cm = manager(t, listOf(cand("x")), backgroundScope, { currentTime }, stats, log)
        cm.start()
        cm.state.first { it is ConnectionState.Online }
        t.connections.last().apply { bytesIn = 100 }.drop() // too little data
        cm.state.first { it is ConnectionState.Waiting }
        advanceTimeBy(2_000)
        cm.state.first { it is ConnectionState.Online }
        t.connections.last().bytesIn = 4096
        cm.reconnect() // our own close: not the censor
        cm.state.first { it is ConnectionState.Online && t.connections.size == 3 }
        assertEquals(0, stats.get("x")!!.throttleCount)
        assertTrue(log.entries.none { it.result == FailureClass.RESET_AFTER_KB })
        cm.stop()
    }


    @Test fun oversizeFrameDropsTheNodeAndTriesTheNextAtOnce() = runTest {
        val t = FakeTransport(delays = mapOf("good" to 500)) { ok() }
        val stats = InMemoryEndpointStats()
        val log = RecordingNetLog()
        val cm = manager(t, listOf(cand("hostile"), cand("good")), backgroundScope, { currentTime }, stats, log)
        cm.start()
        assertEquals("hostile", (cm.state.first { it is ConnectionState.Online } as ConnectionState.Online).url)
        val t0 = currentTime
        t.byUrl["hostile"]!!.last().incoming.trySend(ByteArray(ReceiveLimits.MAX_FRAME + 1))
        withTimeout(10_000) { cm.state.first { it is ConnectionState.Online && it.url == "good" } }
        assertTrue("no backoff wait", currentTime - t0 < 1_000)
        assertEquals(1, stats.get("hostile")!!.failures)
        assertTrue(log.entries.any { it.nodeLabel == "hostile" && it.detail.startsWith("limit:") })
        cm.stop()
    }
}
