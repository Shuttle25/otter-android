package app.ottermsg.core.network

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSelectorTest {
    @Test fun fastSecondBeatsSlowFirst() = runTest {
        val t = FakeTransport(delays = mapOf("slow" to 2_000, "fast" to 100)) { ok() }
        val stats = InMemoryEndpointStats()
        val sel = NodeSelector(t, stats, { currentTime })
        val w = sel.race(listOf(cand("slow"), cand("fast")), backgroundScope)!!
        assertEquals("fast", w.candidate.url)
        assertEquals(350L, currentTime) // started at 250 ms (stagger) + 100 ms connect
        assertEquals(100L, w.rttMs)
        assertEquals(100L, stats.get("fast")!!.rttMs)
        assertTrue("slow loser must not stay connected", t.byUrl["slow"].orEmpty().all { it.closed })
    }

    @Test fun failingFirstSecondWinsWithoutBackoff() = runTest {
        val t = FakeTransport(failUrls = setOf("bad")) { ok() }
        val stats = InMemoryEndpointStats()
        val w = NodeSelector(t, stats, { currentTime }).race(listOf(cand("bad"), cand("good")), backgroundScope)!!
        assertEquals("good", w.candidate.url)
        assertEquals(1, stats.get("bad")!!.failures)
        assertTrue(currentTime <= 250)
    }

    @Test fun missingHelloIsAFailureNotAHang() = runTest {
        val t = FakeTransport(silentUrls = setOf("mute")) { ok() }
        val stats = InMemoryEndpointStats()
        val sel = NodeSelector(t, stats, { currentTime }, helloTimeoutMs = 1_000)
        assertNull(sel.race(listOf(cand("mute")), backgroundScope))
        assertEquals(1, stats.get("mute")!!.failures)
        assertTrue(t.byUrl["mute"]!!.single().closed)
        assertEquals("ok", sel.race(listOf(cand("mute"), cand("ok")), backgroundScope)!!.candidate.url)
    }

    @Test fun allFailReturnsNull() = runTest {
        val t = FakeTransport(failUrls = setOf("a", "b")) { ok() }
        assertNull(NodeSelector(t, InMemoryEndpointStats(), { currentTime }).race(listOf(cand("a"), cand("b")), backgroundScope))
        assertNull(NodeSelector(t, InMemoryEndpointStats(), { currentTime }).race(emptyList(), backgroundScope))
    }

    @Test fun ordering() = runTest {
        val now = 100L * 86_400_000
        val stats = InMemoryEndpointStats()
        stats.success("ok-slow", 300, now - 1_000)
        stats.success("ok-fast", 50, now - 2_000)
        stats.failure("broken", now - 60_000)                   // failed 1 min ago
        stats.success("stale", 10, now - 8L * 86_400_000)        // success too old to count
        stats.failure("old-fail", now - 20 * 60_000)             // failure older than 10 min
        val sel = NodeSelector(FakeTransport { ok() }, stats, { now })
        val order = sel.order(listOf(cand("broken", 0), cand("new2", 2), cand("ok-slow", 9), cand("stale", 3), cand("old-fail", 1), cand("ok-fast", 9)))
        assertEquals(listOf("ok-fast", "ok-slow", "old-fail", "new2", "stale", "broken"), order.map { it.url })
    }
}
