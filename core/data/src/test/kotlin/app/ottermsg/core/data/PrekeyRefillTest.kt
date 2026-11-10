package app.ottermsg.core.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrekeyRefillTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)

    @After fun tearDown() = a.close()

    @Test fun prekeysLowEventTriggersUpload() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val other = node.connect()
        other.login(a.me.userId, a.me.accountKey) // any logged-in session may fetch
        repeat(41) { other.fetchPrekeys(a.me.userId) } // 50 -> 9 left: prekeys_low pushed to A's session
        eventually(what = "refill uploaded") { node.oneTimeLeft(a.me.userId) >= 50 }
        assertEquals(101, a.settings.nextOneTimeId())
        assertFalse(a.settings.prekeysLow())
    }
}
