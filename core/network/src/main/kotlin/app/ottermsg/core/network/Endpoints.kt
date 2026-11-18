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
                    priority = priorityOffset + e.priority * 1000 + order++, viaCdn = e.viaCdn,
                )
            }
        }
    }

    /**
     * Password restore and «Добавить историю» only (D113/D114): the nodes that keep backups directly, plus those nodes
     * through every `no_backups` node's `/tunnel`. A no_backups node answers backup calls with UNAVAILABLE, so it is
     * never a direct candidate here. A tunnel target is a pinned, non-CDN WSS endpoint: the phone runs its own TLS to
     * the target inside the tunnel and pins the target's certificate, so the relay only carries ciphertext.
     */
    fun restoreCandidates(manifest: Manifest, allowPlaintext: Boolean): List<Candidate> {
        val relays = manifest.nodesList.filter { it.noBackups }.map { it.nodeId.toByteArray() }
        fun isRelay(c: Candidate) = relays.any { it.contentEquals(c.nodeId) }
        val all = candidates(manifest, allowPlaintext)
        val direct = all.filterNot(::isRelay)
        val targets = manifest.nodesList.filter { !it.noBackups }.flatMap { n ->
            n.endpointsList.filter { it.kind == Endpoint.Kind.WSS && it.tlsSpkiPinsCount > 0 && !it.viaCdn }.map { n to it }
        }
        val tunnels = all.filter(::isRelay).flatMap { v ->
            targets.map { (n, e) ->
                val id = n.nodeId.toByteArray()
                val host = if (':' in e.host) "[${e.host}]" else e.host
                Candidate(
                    nodeId = id, label = "${n.label} via ${v.label}", url = "wss://$host:${e.port}${e.path}", host = e.host,
                    pins = e.tlsSpkiPinsList.map { it.toByteArray() }, priority = v.priority + TUNNEL_PRIORITY,
                    via = Candidate(v.nodeId, v.label, "${v.url}/tunnel?to=${id.joinToString("") { "%02x".format(it) }}", v.host, v.pins, v.priority),
                )
            }
        }
        return direct + tunnels
    }

    /** Tunnels start after the direct candidates without history (they cost the relay a stream each). */
    private const val TUNNEL_PRIORITY = 1_000_000
}
