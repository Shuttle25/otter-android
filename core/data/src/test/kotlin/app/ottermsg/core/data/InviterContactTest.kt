package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.InviteLink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** C23: the inviter fields of a link are unauthenticated. */
@RunWith(RobolectricTestRunner::class)
class InviterContactTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)
    @After fun tearDown() { a.close(); b.close() }

    private suspend fun inviterOnline(): String {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        return a.invites.create().link
    }

    @Test fun linkContactIsUnconfirmedUntilTheDirectory() = runBlocking {
        b.register(inviterOnline(), "Боря", "borya").getOrThrow()
        val c = b.db.contactDao().get(a.me.userId)!!
        assertFalse(c.verified); assertEquals(0, c.entryVersion)

        // Not sent while only the link vouches for A.
        b.messages.sendText(b.messages.conversationWith(a.me.userId), "рано")
        val s = node.connect().also { it.login(b.me.userId, b.me.accountKey) }
        b.outbox.drainOnce(s)
        assertTrue(node.mailbox(a.me.userId).isEmpty())
        assertEquals(1, b.db.outboxDao().due(Long.MAX_VALUE).size)

        b.directory.sync(s)
        val confirmed = b.db.contactDao().get(a.me.userId)!!
        assertTrue(confirmed.entryVersion > 0); assertFalse(confirmed.verified); assertFalse(confirmed.identityChanged)
        clock.advance(120_000) // past the retry backoff
        b.outbox.drainOnce(s)
        assertEquals(1, node.mailbox(a.me.userId).size)
        s.close()
    }

    @Test fun linkWithAMismatchedInviterStoresNoContact() = runBlocking {
        val real = InviteLink.decode(inviterOnline())
        val evil = Ed25519.keyPairFromSeed(ByteArray(32) { 5 })
        // Same user id as A, someone else's key: a hostile node or a tampered message hands this out.
        val forged = InviteLink.encode(real.toBuilder().setInviterAccountPub(bs(evil.publicKey)).build())
        b.register(forged, "Боря", "borya").getOrThrow()
        assertNull(b.db.contactDao().get(a.me.userId))
        b.online()
        eventually(what = "A from the directory") { (b.db.contactDao().get(a.me.userId)?.entryVersion ?: 0) > 0 }
        assertTrue(b.db.contactDao().get(a.me.userId)!!.accountPub.contentEquals(a.me.accountKey.publicKey))
    }
}
