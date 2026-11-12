package app.ottermsg.core.data.push

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PushRegistrarTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)

    @After fun tearDown() = g.close()

    private suspend fun login(): FakeNode.Session =
        node.connect().also { it.login(g.me.userId, g.me.accountKey) }

    @Test fun sendsOnlyWhenChanged() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val api = login()
        val reg = PushRegistrar(g.accounts, g.settings, g.clock)
        reg.registerIfNeeded(api)                                     // no token yet: nothing
        assertTrue(node.pushTokens.isEmpty())
        g.settings.setPushToken("tok-1")
        reg.registerIfNeeded(api)
        assertEquals("tok-1", node.pushTokens.values.single())
        node.pushTokens.clear()
        reg.registerIfNeeded(api)                                     // unchanged: not sent again
        assertTrue(node.pushTokens.isEmpty())
        g.settings.setPushToken("tok-2")
        reg.registerIfNeeded(api)
        assertEquals("tok-2", node.pushTokens.values.single())
    }

    @Test fun failureKeepsTheTokenPending() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val api = login()
        val reg = PushRegistrar(g.accounts, g.settings, g.clock)
        g.settings.setPushToken("tok-1")
        node.reachable = false
        runCatching { reg.registerIfNeeded(api) }.also { assertTrue(it.exceptionOrNull() is IOException) }
        assertEquals(null, g.settings.pushTokenSent())
        node.reachable = true
        reg.registerIfNeeded(api)
        assertEquals("tok-1", node.pushTokens.values.single())
        assertEquals("tok-1", g.settings.pushTokenSent())
    }
}
