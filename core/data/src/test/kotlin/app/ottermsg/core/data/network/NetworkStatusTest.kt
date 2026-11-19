package app.ottermsg.core.data.network

import app.ottermsg.core.database.dao.EndpointStatDao
import app.ottermsg.core.database.entity.EndpointStatEntity
import app.ottermsg.core.network.Candidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStatusTest {
    private class FakeStats(private val rows: List<EndpointStatEntity>) : EndpointStatDao {
        override suspend fun get(key: String) = rows.firstOrNull { it.key == key }
        override suspend fun upsert(s: EndpointStatEntity) = error("read-only")
        override suspend fun all() = rows
    }

    private fun cand(id: Int, label: String, host: String, port: Int, scheme: String = "wss", cdn: Boolean = false) =
        Candidate(ByteArray(16) { id.toByte() }, label, "$scheme://$host:$port/p", host,
            if (scheme == "wss") listOf(ByteArray(32) { id.toByte() }) else emptyList(), 0, viaCdn = cdn)

    @Test fun groupsTheManifestEndpointsByNodeWithTheirHistory() = runBlocking {
        val fi1 = cand(1, "fi", "h1", 443); val fi2 = cand(1, "fi", "h2", 443, cdn = true); val ru = cand(2, "ru", "h3", 27443)
        val stats = FakeStats(listOf(
            EndpointStatEntity(fi1.key, lastSuccessMs = 100, rttMs = 80),
            EndpointStatEntity(ru.key, lastFailureMs = 300, failures = 4, throttleCount = 3, demotedUntilMs = 5_000),
            EndpointStatEntity("wss://gone:443/p", lastSuccessMs = 1), // an endpoint the manifest no longer lists
        ))
        val nodes = NetworkStatus({ listOf(fi1, fi2, ru) }, stats).nodes()
        assertEquals(listOf(
            NodeRow("fi", listOf(
                EndpointRow(fi1.key, fi1.url, "wss:443", cdn = false, lastSuccessMs = 100, lastFailureMs = null, rttMs = 80, failures = 0, demotedUntilMs = 0),
                EndpointRow(fi2.key, fi2.url, "wss:443", cdn = true, lastSuccessMs = null, lastFailureMs = null, rttMs = null, failures = 0, demotedUntilMs = 0),
            )),
            NodeRow("ru", listOf(
                EndpointRow(ru.key, ru.url, "wss:27443", cdn = false, lastSuccessMs = null, lastFailureMs = 300, rttMs = null, failures = 4, demotedUntilMs = 5_000),
            )),
        ), nodes)
        assertEquals(true, nodes[0].endpoints[1].neverTried)
        assertEquals(false, nodes[1].endpoints[0].neverTried)
    }

    @Test fun ipv6AndPlainEndpointsAreNamedBySchemeAndPortOnly() = runBlocking {
        val v6 = Candidate(ByteArray(16), "a", "wss://[2001:db8::1]:8443/p", "2001:db8::1", emptyList(), 0)
        val ws = cand(3, "b", "h4", 8080, scheme = "ws")
        val nodes = NetworkStatus({ listOf(v6, ws) }, FakeStats(emptyList())).nodes()
        assertEquals(listOf("wss:8443", "ws:8080"), nodes.flatMap { n -> n.endpoints.map { it.name } })
    }

    @Test fun versionFromTheLastLoginPerNodeLabel() = runBlocking {
        val fi = cand(1, "fi", "h1", 443); val ru = cand(2, "ru", "h3", 27443)
        val nodes = NetworkStatus({ listOf(fi, ru) }, FakeStats(emptyList()), { mapOf("fi" to "0.11.0", "gone" to "0.10.1") }).nodes()
        assertEquals(listOf("0.11.0", null), nodes.map { it.version }) // ru never reached: no version
    }

    @Test fun noManifestNoNodes() = runBlocking {
        assertEquals(emptyList<NodeRow>(), NetworkStatus({ emptyList() }, FakeStats(emptyList())).nodes())
    }
}
