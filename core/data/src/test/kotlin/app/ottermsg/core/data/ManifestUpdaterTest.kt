package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.sync.ManifestUpdater
import app.ottermsg.core.data.sync.ManifestUpdater.Result
import app.ottermsg.core.data.sync.ReprobePolicy
import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.NodeInfo
import app.ottermsg.proto.v1.RecordType
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

    /** A manifest of another network: validly signed by its own key (D139). */
    private fun foreignManifest(version: Long): app.ottermsg.proto.v1.Record {
        val k = app.ottermsg.core.crypto.Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
        val m = Manifest.newBuilder().setVersion(version).setIssuedMs(version).setNetworkPub(bs(k.publicKey))
            .addNodes(nodeInfo(1, "evil", "evil", ByteArray(32) { 5 })).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.MANIFEST, Ids.id16(k.publicKey), m.toByteArray(), createdMs = version), k,
            app.ottermsg.core.crypto.SigContext(Ids.id16(k.publicKey), 0))
    }

    @Test fun foreignManifestIsReportedAndNotStored() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow() // stores v1
        val foreign = foreignManifest(9)
        assertEquals(Result.FOREIGN, m.offer(foreign.toByteArray(), node.nodeId))
        assertEquals(1L, g.db.manifestDao().latest()!!.version)
        assertEquals(listOf("fake"), m.candidates(true).map { it.label })
        // a broken signature is noise, not a network change
        val broken = foreign.toBuilder().setSignature(bs(foreign.signature.toByteArray().also { it[3] = (it[3] + 1).toByte() })).build()
        assertEquals(Result.IGNORED, m.offer(broken.toByteArray(), node.nodeId))
    }

    private fun nodeInfo(id: Int, label: String, host: String, pin: ByteArray): NodeInfo =
        NodeInfo.newBuilder().setNodeId(bs(ByteArray(16) { id.toByte() })).setLabel(label)
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost(host).setPort(443).setPath("/p")
                .addTlsSpkiPins(bs(pin))).build()

    private fun manifest(version: Long, vararg nodes: NodeInfo): ByteArray {
        val m = Manifest.newBuilder().setVersion(version).setIssuedMs(version).setNetworkPub(bs(node.network.publicKey))
            .addAllNodes(nodes.toList()).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.MANIFEST, Ids.id16(node.network.publicKey), m.toByteArray(),
            createdMs = version), node.network, node.ctx).toByteArray()
    }

    private suspend fun hosts() = m.candidates(allowPlaintext = false).map { it.host }

    @Test fun fallbackOnlyForNodesAndPinsTheNewestManifestKeeps() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val p = ByteArray(32) { 1 }; val q = ByteArray(32) { 2 }; val r = ByteArray(32) { 3 }
        m.offer(manifest(2, nodeInfo(1, "a", "a1", p), nodeInfo(2, "b", "b1", q)), null)
        m.offer(manifest(3, nodeInfo(1, "a", "a2", p)), null)            // A moved, B removed
        val c = m.candidates(false)
        assertEquals(listOf("a2", "a1"), c.map { it.host })               // B's b1 is gone at once
        assertTrue(c[1].priority >= ManifestUpdater.FALLBACK_PRIORITY)
        m.offer(manifest(4, nodeInfo(1, "a", "a3", r)), null)            // pin P withdrawn
        assertEquals(listOf("a3"), hosts())
    }

    @Test fun removedNodeIsDroppedAtOnceAndFallbackEndsAfterSevenDays() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow() // v1: fake
        assertEquals(listOf("fake"), m.candidates(true).map { it.label })
        assertTrue(m.candidates(allowPlaintext = false).isEmpty()) // release builds drop WS_PLAIN
        m.offer(node.signManifest(2, withSelf = false, extraHosts = listOf("b", "c")).toByteArray(), null)
        assertEquals(listOf("b", "c"), m.candidates(true).map { it.label }) // "fake" was removed: no fallback
        val p = ByteArray(32) { 1 }
        m.offer(manifest(3, nodeInfo(1, "b", "b2", p)), null)
        m.offer(manifest(4, nodeInfo(1, "b", "b3", p)), null)
        assertEquals(listOf("b3", "b2"), hosts())
        clock.advance(ManifestUpdater.FALLBACK_MS)
        assertEquals(listOf("b3"), hosts())
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
