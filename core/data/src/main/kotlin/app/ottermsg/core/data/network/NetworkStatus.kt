package app.ottermsg.core.data.network

import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.sync.ManifestUpdater
import app.ottermsg.core.database.dao.EndpointStatDao
import app.ottermsg.core.network.Candidate
import javax.inject.Inject

/**
 * One endpoint of a node as «Состояние сети» shows it. [name] is `scheme:port` only (like the shared netlog, D96): the
 * screen names nodes by their manifest label, never by address. Times are null when there was none.
 */
data class EndpointRow(
    val key: String, val url: String, val name: String, val cdn: Boolean,
    val lastSuccessMs: Long?, val lastFailureMs: Long?, val rttMs: Long?, val failures: Int, val demotedUntilMs: Long,
) {
    /** «ещё не подключались»: no attempt recorded by this phone. */
    val neverTried: Boolean get() = lastSuccessMs == null && lastFailureMs == null
}

data class NodeRow(val label: String, val endpoints: List<EndpointRow>)

/**
 * «Состояние сети» (D123): the manifest's nodes with this phone's own connection history (`endpoint_stats`), in
 * manifest order. Read-only; nothing is sent anywhere.
 */
class NetworkStatus internal constructor(
    private val candidates: suspend () -> List<Candidate>, private val stats: EndpointStatDao,
) {
    @Inject constructor(manifests: ManifestUpdater, stats: EndpointStatDao, @AllowPlaintext allowPlaintext: Boolean) :
        this({ manifests.candidates(allowPlaintext) }, stats)

    suspend fun nodes(): List<NodeRow> {
        val byKey = stats.all().associateBy { it.key }
        return candidates().groupBy { it.label }.map { (label, cs) ->
            NodeRow(label, cs.map { c ->
                val s = byKey[c.key]
                EndpointRow(c.key, c.url, name(c.url), c.viaCdn, s?.lastSuccessMs?.takeIf { it > 0 },
                    s?.lastFailureMs?.takeIf { it > 0 }, s?.rttMs?.takeIf { it > 0 }, s?.failures ?: 0, s?.demotedUntilMs ?: 0)
            })
        }
    }

    /** `wss://host:443/p` → `wss:443` (IPv6 hosts are bracketed, so the port follows the last colon after them). */
    private fun name(url: String): String {
        val scheme = url.substringBefore("://")
        val hostPort = url.substringAfter("://").substringBefore('/')
        val port = hostPort.substringAfter("]", hostPort).substringAfterLast(':', "")
        return if (port.isEmpty()) scheme else "$scheme:$port"
    }
}
