package app.ottermsg.core.network

import app.ottermsg.proto.v1.Endpoint
import app.ottermsg.proto.v1.Manifest

object Endpoints {
    /** Connection candidates in manifest order: WSS (with pins), WS_PLAIN only if allowed; REALITY is M7. */
    fun candidates(manifest: Manifest, allowPlaintext: Boolean, priorityOffset: Int = 0): List<Candidate> {
        var order = 0
        return manifest.nodesList.flatMap { node ->
            node.endpointsList.mapNotNull { e ->
                val scheme = when (e.kind) {
                    Endpoint.Kind.WSS -> "wss"
                    Endpoint.Kind.WS_PLAIN -> if (allowPlaintext) "ws" else null
                    else -> null
                } ?: return@mapNotNull null
                val host = if (':' in e.host) "[${e.host}]" else e.host
                Candidate(
                    nodeId = node.nodeId.toByteArray(), label = node.label, url = "$scheme://$host:${e.port}${e.path}", host = e.host,
                    pins = if (e.kind == Endpoint.Kind.WSS) e.tlsSpkiPinsList.map { it.toByteArray() } else emptyList(),
                    priority = priorityOffset + e.priority * 1000 + order++,
                )
            }
        }
    }
}
