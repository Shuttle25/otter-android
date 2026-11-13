package app.ottermsg.core.data.calls

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCacheTest {
    private var now = 1_800_000_000_000L
    private val cache = TurnCache { now }

    /** Only nodeId() and turnCredentials() are used; everything else goes to an unused FakeNode session. */
    private class Api(var node: Byte = 1, var fail: Status? = null, val clock: () -> Long) :
        NodeApi by FakeNode({ 0L }).connect() {
        var rpcs = 0
        override suspend fun nodeId() = ByteArray(16) { node }
        override suspend fun turnCredentials(): TurnCredentials {
            rpcs++
            fail?.let { throw RpcException(it, "no") }
            val exp = clock() / 1000 + 43_200
            return TurnCredentials.newBuilder().setUsername("$exp:abcd").setPassword("p$rpcs").setTtlS(43_200)
                .addUrls("turn:203.0.113.7:3478?transport=udp").build()
        }
    }

    @Test fun asksTheNodeOnEveryCall() = runBlocking {
        val api = Api(clock = { now })
        cache.get(api)
        now += 60_000
        assertEquals("p2", cache.get(api)!!.password)
        assertEquals(2, api.rpcs)
    }

    @Test fun turnSwitchedOffOnTheSameNodeIsSeenAtOnce() = runBlocking {
        val api = Api(clock = { now })
        cache.get(api)!!
        api.fail = Status.STATUS_UNAVAILABLE
        assertNull(cache.get(api)) // not the cached credentials of a relay that is gone
        api.fail = Status.STATUS_RETRY_LATER
        assertTrue(runCatching { cache.get(api) }.isFailure) // and the cache was dropped
    }

    @Test fun aFailedRequestFallsBackToValidCredentialsOfThatNode() = runBlocking {
        val api = Api(clock = { now })
        val first = cache.get(api)!!
        api.fail = Status.STATUS_RETRY_LATER
        now += 10 * 3_600_000L
        assertSame(first, cache.get(api))
        now += 90 * 60_000L // less than 1 h left: no fallback
        assertTrue(runCatching { cache.get(api) }.isFailure)
        api.fail = null; api.node = 2
        cache.get(api)
        api.fail = Status.STATUS_RETRY_LATER; api.node = 1
        assertTrue(runCatching { cache.get(api) }.isFailure) // node 1's credentials were replaced by node 2's
    }

    @Test fun nodesWithoutTurnGiveNull() = runBlocking {
        for (s in listOf(Status.STATUS_UNAVAILABLE, Status.STATUS_INVALID, Status.STATUS_NOT_FOUND)) {
            assertNull(cache.get(Api(fail = s, clock = { now })))
        }
    }

    @Test fun otherErrorsPropagate() = runBlocking {
        val e = runCatching { cache.get(Api(fail = Status.STATUS_RETRY_LATER, clock = { now })) }.exceptionOrNull()
        assertTrue(e is RpcException && e.status == Status.STATUS_RETRY_LATER)
    }
}
