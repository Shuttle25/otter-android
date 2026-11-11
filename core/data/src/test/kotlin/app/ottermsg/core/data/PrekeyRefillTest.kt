package app.ottermsg.core.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun refillForAnotherOwnerAndRetryAtLogin() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val other = ByteArray(16) { 0x33 }
        val otherHex = other.joinToString("") { "%02x".format(it) }
        a.session!!.emit("prekeys_low", app.ottermsg.proto.v1.PrekeysLow.newBuilder().setRemaining(2).setOwnerNode(bs(other)).build())
        eventually(what = "keys for the other node uploaded") { node.uploadedByOwner[otherHex] == 50 }
        assertTrue(a.settings.pendingRefills().isEmpty())

        // an interrupted refill is remembered per owner and completed at the next login
        node.reachable = false
        runCatching { a.prekeys.refill(a.session!!, other) }
        assertEquals(setOf(otherHex), a.settings.pendingRefills())
        node.reachable = true
        a.online()
        eventually(what = "pending refill retried") { a.settings.pendingRefills().isEmpty() }
        assertTrue(node.uploadedByOwner[otherHex]!! >= 100)
    }
}
