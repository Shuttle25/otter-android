package app.ottermsg.core.data.calls

import app.ottermsg.core.data.Clock
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TURN-REST credentials of the connected node (D77). Asked for on every call, so a relay that was switched off or got
 * a new secret is noticed at once; the last answer of the same node is only a fallback when the request itself fails
 * (and only until 1 h before it expires).
 */
@Singleton
class TurnCache @Inject constructor(private val clock: Clock) {
    private val lock = Mutex()
    private var cached: Pair<String, TurnCredentials>? = null

    /** Credentials for the node behind [api]; null when calls are not available there. Other errors propagate. */
    suspend fun get(api: NodeApi): TurnCredentials? = lock.withLock {
        val node = api.nodeId().joinToString("") { "%02x".format(it) }
        try {
            api.turnCredentials().also { cached = node to it }
        } catch (e: RpcException) {
            if (e.status in NOT_AVAILABLE) { cached = null; return@withLock null }
            fallback(node) ?: throw e
        } catch (e: java.io.IOException) {
            fallback(node) ?: throw e
        }
    }

    private fun fallback(node: String): TurnCredentials? =
        cached?.let { (n, c) -> c.takeIf { n == node && expiryMs(c) - clock.now() > MARGIN_MS } }

    /** TURN-REST username: "<unix expiry>:<user>". */
    private fun expiryMs(c: TurnCredentials) = (c.username.substringBefore(':').toLongOrNull() ?: 0) * 1000

    companion object {
        private const val MARGIN_MS = 3_600_000L
        /** UNAVAILABLE: TURN off; INVALID: a node from before 0.8.0 (unknown method); NOT_FOUND: likewise. */
        private val NOT_AVAILABLE = setOf(Status.STATUS_UNAVAILABLE, Status.STATUS_INVALID, Status.STATUS_NOT_FOUND)
    }
}
