package app.ottermsg.core.data

import app.ottermsg.core.data.sync.ManifestUpdater
import app.ottermsg.core.data.sync.ManifestUpdater.Result
import app.ottermsg.core.data.sync.ReprobePolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestUpdaterTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val m get() = g.manifests

    @After fun tearDown() = g.close()

    @Test fun newerStoredOlderAndTamperedIgnored() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow() // stores v1
        assertEquals(Result.STORED, m.offer(node.signManifest(2).toByteArray(), node.nodeId))
        assertEquals(2L, g.db.manifestDao().latest()!!.version)
        assertEquals(Result.IGNORED, m.offer(node.signManifest(2).toByteArray(), node.nodeId)) // same version
        assertEquals(Result.IGNORED, m.offer(node.manifest.toByteArray(), node.nodeId))     // older (v1)
        val tampered = node.signManifest(3).toBuilder().setSignature(bs(ByteArray(64))).build()
        assertEquals(Result.IGNORED, m.offer(tampered.toByteArray(), node.nodeId))
        assertEquals(Result.IGNORED, m.offer(byteArrayOf(1, 2, 3), node.nodeId))
        assertEquals(Result.STORED_NODE_REMOVED, m.offer(node.signManifest(4, withSelf = false, extraHosts = listOf("b")).toByteArray(), node.nodeId))
        assertTrue(g.db.manifestDao().latest()!!.receivedMs > 0)
    }

    @Test fun previousEndpointsAreAFallbackForSevenDays() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow() // v1: fake
        assertEquals(listOf("fake"), m.candidates(true).map { it.label })
        m.offer(node.signManifest(2, withSelf = false, extraHosts = listOf("b", "c")).toByteArray(), null)
        val c = m.candidates(true)
        assertEquals(listOf("b", "c", "fake"), c.map { it.label }) // old endpoint kept, last
        assertTrue(c.last().priority > c[1].priority)
        clock.advance(ManifestUpdater.FALLBACK_MS)
        assertEquals(listOf("b", "c"), m.candidates(true).map { it.label })
        assertTrue(m.candidates(allowPlaintext = false).isEmpty()) // release builds drop WS_PLAIN
    }

    @Test fun newsKeysFromTheLatestManifest() = runBlocking {
        assertTrue(m.newsKeys().isEmpty()) // nothing stored yet
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow() // v1: no news keys
        assertTrue(m.newsKeys().isEmpty())
        val k1 = ByteArray(32) { 1 }; val k2 = ByteArray(32) { 2 }
        m.offer(node.signManifest(2, newsKeys = listOf(k1, k2)).toByteArray(), node.nodeId)
        assertEquals(listOf(k1.toList(), k2.toList()), m.newsKeys().map { it.toList() })
        m.offer(node.signManifest(3, newsKeys = listOf(k2)).toByteArray(), node.nodeId) // k1 revoked
        assertEquals(listOf(k2.toList()), m.newsKeys().map { it.toList() })
    }

    @Test fun reprobeNeedsThreeClearWins() {
        val p = ReprobePolicy()
        assertFalse(p.observe(100, 60)); assertFalse(p.observe(100, 60))
        assertFalse(p.observe(100, 80)) // 80 is not < 70 % of 100: streak resets
        assertFalse(p.observe(100, 50)); assertFalse(p.observe(100, null)) // probe found nothing: reset
        assertFalse(p.observe(100, 50)); assertFalse(p.observe(100, 50))
        assertTrue(p.observe(100, 50))
        assertFalse(p.observe(100, 50)) // streak restarts after a switch
    }
}
