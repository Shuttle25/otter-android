package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.RecordBody
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** C15: a hostile node serves a forged copy of an envelope (same author‖id) before the real one. */
@RunWith(RobolectricTestRunner::class)
class ForgedEnvelopeTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)
    private val attacker = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun TestGraph.thread(peer: TestGraph): List<MessageEntity> =
        messages.observeMessages(messages.conversationWith(peer.me.userId)).first()

    /** A and B know each other; A is offline; B's "настоящее" waits on the node. Returns the real record. */
    private suspend fun realEnvelopeWaiting(): app.ottermsg.proto.v1.Record {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow(); b.online()
        eventually(what = "A sees B") { a.db.contactDao().get(b.me.userId)?.entryVersion ?: 0 > 0 }
        a.offline()
        b.messages.sendText(b.messages.conversationWith(a.me.userId), "настоящее")
        eventually(what = "node holds it for A") { node.mailbox(a.me.userId).size == 1 }
        return node.mailbox(a.me.userId).single()
    }

    private suspend fun handOver(forged: app.ottermsg.proto.v1.Record) {
        val s = node.connect().also { it.login(a.me.userId, a.me.accountKey) }
        a.receiver.handle(s, listOf(forged))
        s.close()
        assertEquals("a forged copy must not be acknowledged", 1, node.mailbox(a.me.userId).size)
        assertTrue(a.thread(b).isEmpty())
        a.online()
        eventually(what = "the real message is shown") { a.thread(b).any { it.body == "настоящее" } }
    }

    @Test fun copySignedByAnotherKey() = runBlocking {
        val real = realEnvelopeWaiting()
        handOver(RecordCodec.sign(RecordBody.parseFrom(real.body), attacker))
    }

    @Test fun alteredBodyWithTheSameId() = runBlocking {
        val real = realEnvelopeWaiting()
        val body = RecordBody.parseFrom(real.body)
        val env = Envelope.parseFrom(body.payload).toBuilder().apply {
            setRecipients(0, getRecipients(0).toBuilder().setContent(ByteString.copyFrom(ByteArray(64) { 1 })))
        }.build()
        handOver(RecordCodec.sign(body.toBuilder().setPayload(env.toByteString()).build(), attacker))
    }

    /** D91: decrypted before a crash cut the commit (seen row lost): acked, nothing shown twice, no «не удалось прочитать». */
    @Test fun decryptedButCommitCut() = runBlocking {
        val real = realEnvelopeWaiting()
        a.online()
        eventually(what = "the real message is shown") { a.thread(b).any { it.body == "настоящее" } }
        val body = RecordBody.parseFrom(real.body)
        val key = body.author.toByteArray() + body.id.toByteArray()
        a.db.seenEnvelopeDao().delete(key) // as if the process died between decrypt and commit
        val s = node.connect().also { it.login(a.me.userId, a.me.accountKey) }
        a.receiver.handle(s, listOf(real))
        s.close()
        assertEquals(listOf("настоящее"), a.thread(b).map { it.body })
        assertTrue(a.db.seenEnvelopeDao().exists(key))
    }
}
