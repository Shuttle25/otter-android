package app.ottermsg.core.data.calls

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.FakeNodeTransport
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.network.RemoteNodeApi
import app.ottermsg.core.network.RpcClient
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** turn.credentials and push.status through RemoteNodeApi against FakeNode over the real frame protocol. */
@RunWith(RobolectricTestRunner::class)
class CallRpcFakeTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.cancel(); g.close() }

    private suspend fun api(login: Boolean = true): RemoteNodeApi {
        val c = g.manifests.candidates(true).first()
        val api = RemoteNodeApi(RpcClient(FakeNodeTransport(node).connect(c), scope), clock::now)
        if (login) api.login(g.me.userId, g.me.accountKey)
        return api
    }

    private suspend fun status(block: suspend () -> Unit): Status? =
        runCatching { block() }.exceptionOrNull()?.let { (it as RpcException).status }

    @Test fun turnCredentials() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        assertEquals(Status.STATUS_UNAUTHORIZED, status { api(login = false).turnCredentials() })
        val api = api()
        assertEquals(Status.STATUS_UNAVAILABLE, status { api.turnCredentials() })
        node.turn = TurnCredentials.newBuilder().setUsername("1:ab").setPassword("p").setTtlS(43200)
            .addUrls("turn:203.0.113.7:3478?transport=udp").build()
        assertEquals(node.turn, api.turnCredentials())
    }

    @Test fun pushStatus() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val api = api()
        val me = g.me.userId
        assertFalse(api.pushStatus(me))
        node.pushTokens[me.joinToString("") { "%02x".format(it) }] = "tok"
        assertTrue(api.pushStatus(me))
    }
}
