package app.ottermsg.core.network

import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.NodeInfo
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
}
