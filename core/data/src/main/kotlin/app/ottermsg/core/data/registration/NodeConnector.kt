package app.ottermsg.core.data.registration

import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.NodeSelector
import app.ottermsg.core.network.RemoteNodeApi
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** One-off connection to the first reachable endpoint (used before an account exists). */
interface NodeConnector {
    /** Runs [block] against the first candidate that connects; throws [IOException] if none does. */
    suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T
}

/** Races the candidates like the sync engine does and runs [block] on the winner. */
class TransportConnector(private val selector: NodeSelector) : NodeConnector {
    override suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T = coroutineScope {
        val w = selector.race(candidates, this) ?: throw IOException("no node reachable")
        try {
            block(RemoteNodeApi(w.rpc))
        } finally {
            w.rpc.close()
        }
    }
}
