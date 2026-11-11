package app.ottermsg.core.data.registration

import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RemoteNodeApi
import app.ottermsg.core.network.RpcClient
import app.ottermsg.core.network.Transport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** One-off connection to the first reachable endpoint (used before an account exists). */
interface NodeConnector {
    /** Runs [block] against the first candidate that connects; throws [IOException] if none does. */
    suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T
}

class TransportConnector(private val transport: Transport) : NodeConnector {
    override suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T {
        var last = IOException("no endpoints")
        for (cand in candidates) {
            val conn = try { transport.connect(cand) } catch (e: IOException) { last = e; continue }
            try {
                return coroutineScope {
                    val rpc = RpcClient(conn, this)
                    try {
                        withTimeout(10_000) { rpc.hello.await() }
                        block(RemoteNodeApi(rpc))
                    } finally {
                        rpc.close()
                    }
                }
            } catch (e: IOException) {
                last = e
            } catch (e: TimeoutCancellationException) {
                last = IOException("no hello from ${cand.url}", e)
            }
        }
        throw last
    }
}
