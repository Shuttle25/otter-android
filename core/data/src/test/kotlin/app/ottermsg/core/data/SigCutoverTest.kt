package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D109 (C7): the phone verifies v1/v2 like the node and signs v2 once its newest manifest carries a cut-over. */
@RunWith(RobolectricTestRunner::class)
class SigCutoverTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val b = TestGraph(node, clock)
    private val foreign = SigContext(ByteArray(16) { 0x77 }, 1) // another network's v2 context
    @After fun tearDown() { g.close(); b.close() }

    private val netId get() = Ids.id16(node.network.publicKey)
    private fun isV2(rec: Record, pub: ByteArray) =
        Ed25519.verify(pub, RecordCodec.messageV2(netId, rec.body.toByteArray()), rec.signature.toByteArray())
    private fun isV1(rec: Record, pub: ByteArray) = Ed25519.verify(pub, rec.body.toByteArray(), rec.signature.toByteArray())
    private fun ack(me: ByteArray) = RecordCodec.body(RecordType.ACK, me, byteArrayOf(1))

    @Test fun signsV1UntilTheCutoverThenV2() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val pub = g.me.accountKey.publicKey
        val pre = g.me.sign(ack(g.me.userId))
        assertTrue(isV1(pre, pub)); assertFalse(isV2(pre, pub))
        assertEquals("STORED", g.manifests.offer(node.signManifest(2, sigV1UntilMs = clock.now() + 1000).toByteArray(), null).name)
        val post = g.me.sign(ack(g.me.userId))
        assertTrue(isV2(post, pub)); assertFalse(isV1(post, pub))
    }

    /** After the cut-over a hostile node cannot push an older-style (v1) manifest signed later. */
    @Test fun v1ManifestAfterTheCutoverIsIgnored() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.manifests.offer(node.signManifest(2, sigV1UntilMs = 2).toByteArray(), null) // created_ms = version = 2
        // version 3, created_ms 3 >= 2, v1-signed: not acceptable any more
        val v1 = RecordCodec.sign(RecordBody.parseFrom(node.signManifest(3).body), node.network, SigContext(netId, 0))
        assertEquals("IGNORED", g.manifests.offer(v1.toByteArray(), null).name)
        assertEquals(2L, g.db.manifestDao().latest()!!.version)
    }

    @Test fun foreignNetworkEntryIsNotAContact() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val bad = node.directoryEntryFor(seed = 7, name = "anna", sig = foreign)
        g.directory.apply(listOf(bad))
        assertNull(g.db.contactDao().get(RecordBody.parseFrom(bad.body).author.toByteArray()))
        // the same entry v2-signed for this network is a member
        val good = node.directoryEntryFor(seed = 8, name = "boris", sig = SigContext(netId, 1))
        g.directory.apply(listOf(good))
        assertNotNull(g.db.contactDao().get(RecordBody.parseFrom(good.body).author.toByteArray()))
    }

    @Test fun foreignNetworkRevocationIsIgnored() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val anna = node.directoryEntryFor(seed = 7, name = "anna")
        val annaId = RecordBody.parseFrom(anna.body).author.toByteArray()
        g.directory.apply(listOf(anna))
        val rev = RecordCodec.sign(RecordCodec.body(RecordType.REVOCATION, netId, app.ottermsg.proto.v1.Revocation.newBuilder()
            .setUserId(bs(annaId)).build().toByteArray(), createdMs = clock.now()), node.network, foreign)
        g.directory.apply(listOf(anna, rev))
        assertFalse(g.db.contactDao().get(annaId)!!.revoked)
        val bound = RecordCodec.sign(RecordBody.parseFrom(rev.body), node.network, SigContext(netId, 1))
        g.directory.apply(listOf(anna, bound))
        assertTrue(g.db.contactDao().get(annaId)!!.revoked)
    }

    /** A link made before the cut-over, used after it: the phone signs as the node's newer manifest says. */
    @Test fun registrationWithAnOldLinkAfterTheCutover() = runBlocking {
        val link = node.bootstrapLink()
        node.publish(node.signManifest(2, sigV1UntilMs = clock.now()), 2) // the node now refuses v1 created from here on
        g.register(link, "Я", "me_user").getOrThrow()
        assertEquals(2L, g.db.manifestDao().latest()!!.version)
        assertTrue(isV2(g.me.sign(ack(g.me.userId)), g.me.accountKey.publicKey))
    }

    // ---- envelopes ----

    private suspend fun TestGraph.thread(peer: TestGraph): List<MessageEntity> =
        messages.observeMessages(messages.conversationWith(peer.me.userId)).first()

    /** A and B know each other; A is offline; B's message waits on the node. Returns the waiting record. */
    private suspend fun envelopeWaiting(text: String): Record {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); g.online()
        b.register(g.invites.create().link, "Боря", "borya").getOrThrow(); b.online()
        eventually(what = "A sees B") { g.db.contactDao().get(b.me.userId)?.entryVersion ?: 0 > 0 }
        g.offline()
        b.messages.sendText(b.messages.conversationWith(g.me.userId), text)
        eventually(what = "node holds it for A") { node.mailbox(g.me.userId).size == 1 }
        return node.mailbox(g.me.userId).single()
    }

    private suspend fun deliver(rec: Record) {
        val s = node.connect().also { it.login(g.me.userId, g.me.accountKey) }
        g.receiver.handle(s, listOf(rec))
        s.close()
    }

    /** Hostile node: the sender's own envelope, re-signed by the sender for another network, is not a message. */
    @Test fun foreignNetworkEnvelopeIsDropped() = runBlocking {
        val real = envelopeWaiting("настоящее")
        deliver(RecordCodec.sign(RecordBody.parseFrom(real.body), b.me.accountKey, foreign))
        assertTrue(g.thread(b).isEmpty())
        // not acked: an ACK for a record that does not verify would tombstone the real envelope (C15)
        assertEquals(1, node.mailbox(g.me.userId).size)
        g.online()
        eventually(what = "the real message is shown") { g.thread(b).any { it.body == "настоящее" } }
    }

    @Test fun v1EnvelopeCreatedAfterTheCutoverIsDropped() = runBlocking {
        val real = envelopeWaiting("старое")
        assertTrue(isV1(real, b.me.accountKey.publicKey)) // B has no cut-over yet
        val created = RecordBody.parseFrom(real.body).createdMs
        g.manifests.offer(node.signManifest(2, sigV1UntilMs = created).toByteArray(), null)
        deliver(real)
        assertTrue(g.thread(b).isEmpty())
        assertEquals(1, node.mailbox(g.me.userId).size)
        // a cut-over after the envelope's creation: v1 still valid for it
        g.manifests.offer(node.signManifest(3, sigV1UntilMs = created + 1).toByteArray(), null)
        deliver(real)
        assertEquals(listOf("старое"), g.thread(b).map { it.body })
    }

    /** The outgoing envelope (captured on the node) is v1 before the sender stores a cut-over manifest and v2 after. */
    @Test fun outgoingEnvelopeIsV2AfterTheCutover() = runBlocking {
        val first = envelopeWaiting("раз")
        val pub = b.me.accountKey.publicKey
        assertTrue(isV1(first, pub))
        b.manifests.offer(node.signManifest(2, sigV1UntilMs = clock.now() + 1000).toByteArray(), null)
        b.messages.sendText(b.messages.conversationWith(g.me.userId), "два")
        eventually(what = "second envelope on the node") { node.mailbox(g.me.userId).size == 2 }
        val second = node.mailbox(g.me.userId).single { !it.body.equals(first.body) }
        assertTrue(isV2(second, pub)); assertFalse(isV1(second, pub))
        // the receiver still on a v1 manifest accepts both
        g.online()
        eventually(what = "both shown") { g.thread(b).map { it.body }.containsAll(listOf("раз", "два")) }
    }
}
