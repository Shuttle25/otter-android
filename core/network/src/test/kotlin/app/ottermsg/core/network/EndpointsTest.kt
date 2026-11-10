package app.ottermsg.core.network

import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.NodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointsTest {
    private val m = Manifest.newBuilder().addNodes(
        NodeInfo.newBuilder()
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("a").setPort(443).setPath("/p"))
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WS_PLAIN).setHost("b").setPort(8080).setPath("/q"))
            .addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.REALITY).setHost("c").setPort(443)),
    ).addNodes(
        NodeInfo.newBuilder().addEndpoints(Endpoint.newBuilder().setKind(Endpoint.Kind.WSS).setHost("d").setPort(8443).setPath("/r")),
    ).build()

    @Test fun releaseRefusesPlaintext() {
        assertEquals(listOf("wss://a:443/p", "wss://d:8443/r"), Endpoints.urls(m, allowPlaintext = false))
    }

    @Test fun debugAllowsPlaintextInManifestOrder() {
        assertEquals(listOf("wss://a:443/p", "ws://b:8080/q", "wss://d:8443/r"), Endpoints.urls(m, allowPlaintext = true))
    }
}
