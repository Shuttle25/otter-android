package app.ottermsg.core.network

import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest

object Endpoints {
    fun urls(manifest: Manifest, allowPlaintext: Boolean): List<String> = manifest.nodesList.flatMap { node ->
        node.endpointsList.mapNotNull { e ->
            when (e.kind) {
                Endpoint.Kind.WSS -> "wss://${e.host}:${e.port}${e.path}"
                Endpoint.Kind.WS_PLAIN -> if (allowPlaintext) "ws://${e.host}:${e.port}${e.path}" else null
                else -> null
            }
        }
    }
}
