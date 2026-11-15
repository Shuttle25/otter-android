package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.Transport
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * `otter-admin user revoke` (C2): the disabled phone shows «Аккаунт отключён» and stops retrying — but only when it can
 * verify it (its own network-signed REVOCATION, or REVOKED from two manifest nodes); a lone node lying REVOKED loses.
 */
@RunWith(RobolectricTestRunner::class)
class AccountRevokedTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)

    @After fun tearDown() { a.close(); b.close() }

    /** A and B registered over a manifest that also lists [extraHosts]; B has one unsent message to A. */
    private suspend fun setup(extraHosts: List<String> = emptyList()): Long {
        if (extraHosts.isNotEmpty()) node.publish(node.signManifest(2, extraHosts = extraHosts), 2)
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow()
        a.offline()
        val s = node.connect()
        s.login(b.me.userId, b.me.accountKey)
        b.directory.sync(s) // A confirmed by the directory (C23)
        s.close()
        val local = b.messages.sendText(b.messages.conversationWith(a.me.userId), "привет")!!
        assertEquals(MessageStatus.PENDING, b.db.messageDao().get(local)!!.status)
        return local
    }

    /** A node with another id that answers REVOKED to everything (it knows no accounts at all). */
    private fun liar(id: Int) = FakeNodeTransport(FakeNode(clock::now, ByteArray(16) { id.toByte() }).apply { answerRevoked = true })

    private suspend fun assertStopped(local: Long) {
        assertTrue(b.settings.accountRevoked())
        assertTrue(b.settings.observeAccountRevoked().first())
        eventually(what = "outbox cancelled") { b.db.outboxDao().due(Long.MAX_VALUE).isEmpty() }
        eventually(what = "message failed") { b.db.messageDao().get(local)!!.status == MessageStatus.FAILED }
        eventually(what = "offline") { b.engine.connection.value is ConnectionState.Offline }
        val connects = b.transport.connects
        b.engine.release(SyncEngine.FOREGROUND); b.engine.acquire(SyncEngine.FOREGROUND); b.engine.startWake()
        delay(300)
        assertEquals(connects, b.transport.connects) // never connects again
        assertTrue(b.engine.connection.value is ConnectionState.Offline)
    }

    @Test fun ownSignedRevocationDisablesTheAccount() = runBlocking {
        val local = setup()
        node.revoke(b.me.userId) // a node that has not applied it yet still serves the record
        b.online()
        assertStopped(local)
    }

    @Test fun forgedRevocationIsIgnored() = runBlocking {
        val local = setup()
        node.revoke(b.me.userId, signer = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })) // a node's own "revocation"
        b.online()
        assertFalse(b.settings.accountRevoked())
        assertEquals(MessageStatus.PENDING, b.db.messageDao().get(local)!!.status)
    }

    @Test fun loneNodeLyingRevokedIsNotBelieved() = runBlocking {
        val local = setup(extraHosts = listOf("b"))
        val lying = liar(1)
        b.routes["b"] = lying
        b.transport.delayMs = 1_000 // the liar wins the race
        b.engine.acquire(SyncEngine.FOREGROUND)
        val online = b.engine.connection.first { it is ConnectionState.Online } as ConnectionState.Online
        assertEquals("fake", online.nodeLabel) // the honest node is used
        assertTrue(lying.connects >= 1)
        eventually(what = "message sent") { b.db.messageDao().get(local)!!.status == MessageStatus.SENT }
        assertFalse(b.settings.accountRevoked())
        assertEquals(1, node.mailbox(a.me.userId).size)
    }

    @Test fun twoManifestNodesAnsweringRevokedDisableTheAccount() = runBlocking {
        val local = setup(extraHosts = listOf("b", "c"))
        b.routes["b"] = liar(1)
        b.routes["c"] = liar(2)
        b.routes["fake"] = object : Transport { // the third node is down
            override suspend fun connect(candidate: Candidate) = throw IOException("down")
        }
        b.engine.acquire(SyncEngine.FOREGROUND)
        eventually(what = "revoked") { b.settings.accountRevoked() }
        b.routes.remove("fake")
        assertStopped(local)
    }

    @Test fun oneNodeClaimingAnotherIdCountsOnce() = runBlocking {
        val local = setup(extraHosts = listOf("b"))
        val lying = liar(0x42) // the node at endpoint "b" presents the honest node's id in its hello
        b.routes["b"] = lying
        b.routes["fake"] = object : Transport { // the honest node is down for now
            override suspend fun connect(candidate: Candidate) = throw IOException("down")
        }
        b.engine.acquire(SyncEngine.FOREGROUND)
        eventually(what = "liar tried") { lying.connects >= 1 }
        delay(2_500) // a few backoff rounds
        assertFalse(b.settings.accountRevoked())
        assertEquals(1, lying.connects) // failed for this run of the app: not raced again
        b.routes.remove("fake")
        b.engine.retryNow()
        val online = b.engine.connection.first { it is ConnectionState.Online } as ConnectionState.Online
        assertEquals("fake", online.nodeLabel)
        eventually(what = "message sent") { b.db.messageDao().get(local)!!.status == MessageStatus.SENT }
        assertFalse(b.settings.accountRevoked())
    }

    @Test fun revokedSendAloneIsNotFinal() = runBlocking {
        val local = setup()
        val s = node.connect()
        s.login(b.me.userId, b.me.accountKey)
        node.failSend = Status.STATUS_REVOKED // one node says so: the message waits, the engine decides
        b.outbox.drainOnce(s)
        assertEquals(MessageStatus.PENDING, b.db.messageDao().get(local)!!.status)
        assertEquals(1, b.db.outboxDao().due(Long.MAX_VALUE).size)
    }
}
