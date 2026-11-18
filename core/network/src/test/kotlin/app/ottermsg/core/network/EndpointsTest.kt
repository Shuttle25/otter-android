package app.ottermsg.core.network

import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.NodeInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointsTest {
    private val m = Manifest.newBuilder().addNodes(
        NodeInfo.newBuilder()
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("a").setPort(443).setPath("/p").addTlsSpkiPins(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { 1 })))
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost("b").setPort(8080).setPath("/q"))
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.REALITY).setHost("c").setPort(443)),
    ).addNodes(
        NodeInfo.newBuilder().addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("d").setPort(8443).setPath("/r")),
    ).build()

    @Test fun releaseRefusesPlaintext() {
        assertEquals(listOf("wss://a:443/p", "wss://d:8443/r"), Endpoints.candidates(m, allowPlaintext = false).map { it.url })
    }

    @Test fun debugAllowsPlaintextInManifestOrder() {
        assertEquals(listOf("wss://a:443/p", "ws://b:8080/q", "wss://d:8443/r"), Endpoints.candidates(m, allowPlaintext = true).map { it.url })
    }

    @Test fun pinsOnlyForWss() {
        val c = Endpoints.candidates(m, allowPlaintext = true)
        assertEquals(1, c[0].pins.size)
        assertEquals(0, c[1].pins.size)
        assertEquals("a", c[0].host)
        assertEquals(true, c[0].key != c[0].url) // pinned endpoints have a pin-specific stats key
        assertEquals(c[1].url, c[1].key)
        assertEquals(listOf(0, 1, 2), c.map { it.priority }) // manifest order within the same endpoint priority
    }

    private fun pin(b: Int) = com.google.protobuf.ByteString.copyFrom(ByteArray(32) { b.toByte() })
    private fun id(b: Int) = com.google.protobuf.ByteString.copyFrom(ByteArray(16) { b.toByte() })
    private val ruId = id(9)
    private val f1Id = id(1)
    private val f2Id = id(2)
    /** ru keeps no backups (D113); f1 is pinned, f2 only behind a CDN (unpinned), f3 on an IPv6 address. */
    private val nb = Manifest.newBuilder()
        .addNodes(NodeInfo.newBuilder().setNodeId(ruId).setLabel("ru").setNoBackups(true)
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("ru").setPort(443).setPath("/rp").addTlsSpkiPins(pin(9))))
        .addNodes(NodeInfo.newBuilder().setNodeId(f1Id).setLabel("f1")
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("f1").setPort(8443).setPath("/p1").addTlsSpkiPins(pin(1))))
        .addNodes(NodeInfo.newBuilder().setNodeId(f2Id).setLabel("f2")
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("f2").setPort(443).setPath("/p2").setViaCdn(true)))
        .build()

    @Test fun restoreCandidatesSkipNoBackupsNodesAndTunnelThroughThem() {
        val c = Endpoints.restoreCandidates(nb, allowPlaintext = false)
        assertEquals(listOf("wss://f1:8443/p1", "wss://f2:443/p2", "wss://f1:8443/p1"), c.map { it.url })
        assertEquals(listOf(null, null, "wss://ru:443/rp/tunnel?to=01010101010101010101010101010101"), c.map { it.via?.url })
        // a no_backups node answers backup calls with UNAVAILABLE: never a direct restore candidate
        assertEquals(true, c.none { it.via == null && it.nodeId.contentEquals(ruId.toByteArray()) })
        val t = c[2]
        assertArrayEquals(f1Id.toByteArray(), t.nodeId)
        assertEquals(listOf(pin(1)), t.pins.map { com.google.protobuf.ByteString.copyFrom(it) }) // TLS pinned to the target
        assertEquals(listOf(pin(9)), t.via!!.pins.map { com.google.protobuf.ByteString.copyFrom(it) })
        assertEquals("f1 via ru", t.label)
        assertEquals(3, c.map { it.key }.toSet().size)
    }

    @Test fun normalCandidatesStillListNoBackupsNodes() {
        assertEquals(listOf("wss://ru:443/rp", "wss://f1:8443/p1", "wss://f2:443/p2"), Endpoints.candidates(nb, allowPlaintext = false).map { it.url })
        assertEquals(true, Endpoints.candidates(nb, allowPlaintext = false).all { it.via == null })
        assertEquals(listOf(false, false, true), Endpoints.candidates(nb, allowPlaintext = false).map { it.viaCdn }) // «Состояние сети» marks CDN endpoints
    }

    @Test fun tunnelTargetsAreOnlyPinnedNonCdnWssOfNodesWithBackups() {
        val m = nb.toBuilder()
            .addNodes(NodeInfo.newBuilder().setNodeId(id(3)).setLabel("f3")
                .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("2001:db8::3").setPort(443).setPath("/p3").addTlsSpkiPins(pin(3)))
                .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("f3cdn").setPort(443).setPath("/p3").setViaCdn(true).addTlsSpkiPins(pin(3)))
                .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost("f3").setPort(80).setPath("/p3")))
            .addNodes(NodeInfo.newBuilder().setNodeId(id(8)).setLabel("ru2").setNoBackups(true)
                .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("ru2").setPort(443).setPath("/r2").addTlsSpkiPins(pin(8))))
            .build()
        val tunnels = Endpoints.restoreCandidates(m, allowPlaintext = true).filter { it.via != null }
        assertEquals(listOf("f1 via ru", "f3 via ru", "f1 via ru2", "f3 via ru2"), tunnels.map { it.label })
        assertEquals("wss://[2001:db8::3]:443/p3", tunnels[1].url)
        assertEquals(true, tunnels.all { it.pins.isNotEmpty() && it.url.startsWith("wss://") })
        assertEquals(true, tunnels.none { t -> t.nodeId.contentEquals(ruId.toByteArray()) || t.nodeId.contentEquals(id(8).toByteArray()) })
    }
}
