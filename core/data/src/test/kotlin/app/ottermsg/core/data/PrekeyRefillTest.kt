package app.ottermsg.core.data

import app.ottermsg.core.data.signal.PrekeyRefill
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
        node.manifest = node.signManifest(1, extraHosts = listOf("other"))
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val other = ByteArray(16) { 1 }
        val otherHex = other.joinToString("") { "%02x".format(it) }
        a.session!!.emit("prekeys_low", app.ottermsg.proto.v1.PrekeysLow.newBuilder().setRemaining(2).setOwnerNode(bs(other)).build())
        eventually(what = "keys for the other node uploaded") { synchronized(node) { node.uploadedByOwner[otherHex] } == 50 }
        // the marker is cleared right after the upload returns, on the event coroutine: wait for it too
        eventually(what = "pending marker cleared") { a.settings.pendingRefills().isEmpty() }

        // an interrupted refill is remembered per owner and completed at the next login
        clock.advance(PrekeyRefill.MIN_GAP_MS) // one refill per owner per 5 min (D108)
        node.reachable = false
        runCatching { a.prekeys.refill(a.session!!, other) }
        assertEquals(setOf(otherHex), a.settings.pendingRefills())
        node.reachable = true
        clock.advance(PrekeyRefill.MIN_GAP_MS) // the retry also takes a refill slot (D108)
        a.online()
        eventually(what = "pending refill retried") { a.settings.pendingRefills().isEmpty() }
        assertTrue(node.uploadedByOwner[otherHex]!! >= 100)
    }

    @Test fun ownersOutsideTheManifestAreIgnored() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val next = a.settings.nextOneTimeId()
        val stranger = ByteArray(16) { 0x77 }
        a.session!!.emit("prekeys_low", app.ottermsg.proto.v1.PrekeysLow.newBuilder().setRemaining(0).setOwnerNode(bs(stranger)).build())
        Thread.sleep(300)
        assertEquals(next, a.settings.nextOneTimeId())
        assertTrue(a.settings.pendingRefills().isEmpty())
        assertEquals(null, synchronized(node) { node.uploadedByOwner[stranger.joinToString("") { "%02x".format(it) }] })
    }

    @Test fun unknownPendingOwnersAreDroppedAtLogin() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val strangerHex = "77".repeat(16)
        a.settings.setPendingRefills(setOf(strangerHex))
        val next = a.settings.nextOneTimeId()
        a.online()
        eventually(what = "unknown owner dropped") { a.settings.pendingRefills().isEmpty() }
        assertEquals(next, a.settings.nextOneTimeId())
        assertEquals(null, synchronized(node) { node.uploadedByOwner[strangerHex] })
    }

    @Test fun repeatedPrekeysLowIsRateLimited() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val self = node.nodeId.joinToString("") { "%02x".format(it) }
        val before = synchronized(node) { node.uploadedByOwner[self] ?: 0 }
        repeat(20) { a.prekeys.refill(a.session!!, node.nodeId) }
        assertEquals(before + PrekeyRefill.BATCH, synchronized(node) { node.uploadedByOwner[self] })
        clock.advance(5 * 60_000L)
        a.prekeys.refill(a.session!!, node.nodeId)
        assertEquals(before + 2 * PrekeyRefill.BATCH, synchronized(node) { node.uploadedByOwner[self] })
        repeat(10) { clock.advance(5 * 60_000L); a.prekeys.refill(a.session!!, node.nodeId) }
        assertEquals(before + PrekeyRefill.MAX_PER_DAY * PrekeyRefill.BATCH, synchronized(node) { node.uploadedByOwner[self] })
        // a restart does not reset the count
        val fresh = PrekeyRefill(a.accounts, a.settings, a.signal, clock, a.db.manifestDao())
        fresh.refill(a.session!!, node.nodeId)
        assertEquals(before + PrekeyRefill.MAX_PER_DAY * PrekeyRefill.BATCH, synchronized(node) { node.uploadedByOwner[self] })
        // a day later the budget is back
        clock.advance(24 * 3_600_000L)
        fresh.refill(a.session!!, node.nodeId)
        assertEquals(before + (PrekeyRefill.MAX_PER_DAY + 1) * PrekeyRefill.BATCH, synchronized(node) { node.uploadedByOwner[self] })
    }
}
