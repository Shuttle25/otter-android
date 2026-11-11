package app.ottermsg.core.data

import app.ottermsg.core.data.messaging.GroupReceiver
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.GroupEvent
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
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

/** Groups end to end: 5 clients, real libsignal (1:1 + sender keys), FakeNode fan-out of SENDER_KEY envelopes. */
@RunWith(RobolectricTestRunner::class)
class GroupFlowTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)
    private val c = TestGraph(node, clock)
    private val d = TestGraph(node, clock)
    private val e = TestGraph(node, clock)
    private val all = listOf(a, b, c, d, e)

    @After fun tearDown() = all.forEach { it.close() }

    private suspend fun family(vararg gs: TestGraph = arrayOf(a, b, c, d, e)) {
        a.register(node.bootstrapLink(), "Анна", "anna").getOrThrow()
        a.online()
        val names = listOf("Борис" to "boris", "Вера" to "vera", "Гриша" to "grisha", "Даша" to "dasha")
        gs.drop(1).forEachIndexed { i, g ->
            g.register(a.invites.create().link, names[i].first, names[i].second).getOrThrow()
            g.online()
        }
        eventually(what = "everyone knows everyone") {
            gs.all { x -> gs.all { y -> x === y || x.db.contactDao().get(y.me.userId) != null } }
        }
    }

    private suspend fun TestGraph.conv(gid: ByteArray): Long? = db.conversationDao().byGroup(gid)?.id
    private suspend fun TestGraph.msgs(gid: ByteArray): List<MessageEntity> = conv(gid)?.let { db.messageDao().observe(it).first() } ?: emptyList()
    private suspend fun TestGraph.texts(gid: ByteArray) = msgs(gid).filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun TestGraph.events(gid: ByteArray) =
        msgs(gid).filter { it.kind == MessageKind.SYSTEM_GROUP }.map { GroupEvent.parseFrom(it.systemData).type }
    private suspend fun TestGraph.version(gid: ByteArray) = db.groupDao().get(gid)?.version
    private suspend fun TestGraph.memberCount(gid: ByteArray) = db.groupDao().members(gid).size
    private suspend fun create(name: String, family: Boolean, vararg members: TestGraph): ByteArray {
        val cid = a.groups.create(name, family, members.map { it.me.userId })
        return checkNotNull(a.db.conversationDao().get(cid)!!.groupId)
    }
    private suspend fun send(g: TestGraph, gid: ByteArray, text: String): Long = checkNotNull(g.messages.sendText(g.conv(gid)!!, text))
    private fun author(r: Record) = RecordBody.parseFrom(r.body).author.toByteArray()

    /** Plan scenarios 1–5: create, send + per-recipient receipts, add (D35), remove + rotation, leave. */
    @Test fun lifecycle() = runBlocking {
        family()
        // 1. A creates {A,B,C,D}
        val gid = create("Семья", true, b, c, d)
        for (g in listOf(b, c, d)) eventually(what = "group reaches a member") { g.version(gid) == 1L && g.memberCount(gid) == 4 }
        for (g in listOf(a, b, c, d)) {
            assertEquals("Семья", g.db.conversationDao().get(g.conv(gid)!!)!!.title)
            assertTrue(g.db.groupDao().get(gid)!!.family)
            assertEquals(listOf(GroupEvent.Type.CREATED), g.events(gid))
        }
        assertNull(e.conv(gid))

        // 2. B sends while D is offline: "2 of 3" before the last receipt, DELIVERED after it
        d.goOffline()
        val hi = send(b, gid, "привет")
        for (g in listOf(a, c)) eventually(what = "member decrypts") { "привет" in g.texts(gid) }
        eventually(what = "2 of 3 delivered") { b.db.messageRecipientDao().counts(hi).delivered == 2 }
        assertEquals(3, b.db.messageRecipientDao().counts(hi).total)
        assertEquals(MessageStatus.SENT, b.db.messageDao().get(hi)!!.status)
        d.online()
        eventually(what = "D decrypts") { "привет" in d.texts(gid) }
        eventually(what = "B sees DELIVERED") { b.db.messageDao().get(hi)!!.status == MessageStatus.DELIVERED }
        assertTrue("outbox row dropped once delivered", b.db.outboxDao().forMessage(hi).isEmpty())
        a.messages.markConversationRead(a.conv(gid)!!); c.messages.markConversationRead(c.conv(gid)!!); d.messages.markConversationRead(d.conv(gid)!!)
        eventually(what = "B sees READ") { b.db.messageDao().get(hi)!!.status == MessageStatus.READ }

        // 3. A adds E: E gets later messages only (D35) and can write
        a.groups.addMembers(gid, listOf(e.me.userId))
        for (g in listOf(b, c, d, e)) eventually(what = "v2 everywhere") { g.version(gid) == 2L }
        assertTrue(e.db.groupDao().get(gid)!!.iAmMember)
        send(a, gid, "добро пожаловать")
        eventually(what = "E reads the welcome") { "добро пожаловать" in e.texts(gid) }
        assertFalse("E never sees history", "привет" in e.texts(gid))
        send(e, gid, "я тут")
        for (g in listOf(a, b, c, d)) eventually(what = "E's first message arrives") { "я тут" in g.texts(gid) }

        // 4. A removes C: everyone rotates; C cannot read the next message; C is read-only
        val oldDist = a.db.groupDao().myDistribution(gid)!!.distributionId
        a.groups.removeMember(gid, c.me.userId)
        for (g in listOf(b, c, d, e)) eventually(what = "v3 everywhere") { g.version(gid) == 3L }
        assertFalse(c.db.groupDao().get(gid)!!.iAmMember)
        assertTrue(GroupEvent.Type.YOU_WERE_REMOVED in c.events(gid))
        assertNull("composer disabled: sending is refused", c.messages.sendText(c.conv(gid)!!, "эй"))
        for (g in listOf(a, b, d, e)) assertNull("rotation: own sender key dropped", g.db.groupDao().myDistribution(gid))
        d.goOffline()
        send(a, gid, "секрет")
        eventually(what = "the secret waits for D") { node.mailbox(d.me.userId).any { author(it).contentEquals(a.me.userId) && isGroup(it) } }
        val secret = node.mailbox(d.me.userId).last { author(it).contentEquals(a.me.userId) && isGroup(it) }
        assertFalse(a.db.groupDao().myDistribution(gid)!!.distributionId.contentEquals(oldDist))
        // C somehow gets the ciphertext: no key for the new distribution → pending, never shown
        c.groupReceiver.onEnvelope(a.me.userId, byteArrayOf(1, 2, 3), secret, RecordBody.parseFrom(secret.body),
            app.ottermsg.proto.v1.Envelope.parseFrom(RecordBody.parseFrom(secret.body).payload))
        assertFalse("секрет" in c.texts(gid))
        clock.advance(GroupReceiver.PENDING_SHOW_AFTER_MS + 1)
        c.groupReceiver.expirePending()
        assertTrue(GroupEvent.Type.UNDECRYPTABLE in c.events(gid))
        assertFalse("секрет" in c.texts(gid))
        d.online()
        for (g in listOf(b, d, e)) eventually(what = "members read the secret") { "секрет" in g.texts(gid) }

        // 5. D leaves: accepted by all; the remaining senders rotate
        send(b, gid, "до ухода")
        eventually(what = "B has a sender key") { b.db.groupDao().myDistribution(gid) != null }
        d.groups.leave(gid)
        for (g in listOf(a, b, e)) eventually(what = "v4 everywhere") { g.version(gid) == 4L && g.memberCount(gid) == 3 }
        assertTrue(GroupEvent.Type.LEFT in a.events(gid))
        assertFalse(d.db.groupDao().get(gid)!!.iAmMember)
        assertNull(b.db.groupDao().myDistribution(gid))
        send(b, gid, "после ухода")
        for (g in listOf(a, e)) eventually(what = "remaining members read it") { "после ухода" in g.texts(gid) }
        eventually(what = "aggregate over the frozen set") { b.db.messageRecipientDao().counts(b.msgs(gid).last().localId).total == 2 }
        assertFalse("после ухода" in d.texts(gid))
    }

    /** Offline only after everything delivered so far was acked (no stale records left in the mailbox). */
    private suspend fun TestGraph.goOffline() {
        eventually(what = "mailbox drained") { node.mailbox(me.userId).isEmpty() }
        offline()
    }

    private fun isGroup(r: Record) =
        app.ottermsg.proto.v1.Envelope.parseFrom(RecordBody.parseFrom(r.body).payload).kind == app.ottermsg.proto.v1.Envelope.Kind.SENDER_KEY

    /** Scenario 6: v3 (signed by B after A promoted B in v2) reaches D before v2 → parked, then applied after v2. */
    @Test fun outOfOrderUpdateIsParkedThenApplied() = runBlocking {
        family(a, b, c, d)
        val gid = create("Дом", false, b, c, d)
        for (g in listOf(b, c, d)) eventually(what = "v1") { g.version(gid) == 1L }
        d.goOffline()
        a.groups.setAdmin(gid, b.me.userId, true)
        eventually(what = "B is admin") { b.db.groupDao().members(gid).any { it.admin && it.userId.contentEquals(b.me.userId) } }
        b.groups.rename(gid, "Дача")
        eventually(what = "both updates wait for D") {
            node.mailbox(d.me.userId).let { box -> box.any { author(it).contentEquals(a.me.userId) } && box.any { author(it).contentEquals(b.me.userId) } }
        }
        val box = node.mailbox(d.me.userId)
        val fromB = box.filter { author(it).contentEquals(b.me.userId) }
        val fromA = box.filter { author(it).contentEquals(a.me.userId) }
        val s = node.connect().also { it.login(d.me.userId, d.me.accountKey) }
        d.receiver.handle(s, fromB)
        assertEquals(1L, d.version(gid))
        assertEquals(1, d.db.groupDao().pendingUpdates(gid).size)
        d.receiver.handle(s, fromA)
        assertEquals(3L, d.version(gid))
        assertEquals("Дача", d.db.conversationDao().get(d.conv(gid)!!)!!.title)
        assertTrue(d.db.groupDao().pendingUpdates(gid).isEmpty())
        s.close()
    }

    /** Scenario 7: A renames to "X" and B to "Y" at the same version → every client converges on the same state. */
    @Test fun concurrentRenamesConverge() = runBlocking {
        family(a, b, c)
        val gid = create("Дом", false, b, c)
        for (g in listOf(b, c)) eventually(what = "v1") { g.version(gid) == 1L }
        a.groups.setAdmin(gid, b.me.userId, true)
        for (g in listOf(b, c)) eventually(what = "v2") { g.version(gid) == 2L }
        a.goOffline(); b.goOffline()
        a.groups.rename(gid, "X")
        b.groups.rename(gid, "Y")
        a.online(); b.online()
        val names = suspend { listOf(a, b, c).map { it.db.groupDao().get(gid)!!.name } }
        eventually(what = "converged") { names().toSet().size == 1 && listOf(a, b, c).all { it.version(gid) == 3L } }
        val winner = if (app.ottermsg.core.crypto.GroupRules.compareUnsigned(a.me.userId, b.me.userId) < 0) "X" else "Y"
        assertEquals(winner, names().first())
        for (g in listOf(a, b, c)) assertEquals(winner, g.db.conversationDao().get(g.conv(gid)!!)!!.title)
    }

    /** Scenario 8: a group message that arrives before its sender key waits, then shows exactly once. */
    @Test fun messageBeforeSenderKeyWaitsThenShowsOnce() = runBlocking {
        family(a, b, c)
        val gid = create("Дом", false, b, c)
        for (g in listOf(b, c)) eventually(what = "v1") { g.version(gid) == 1L }
        c.goOffline()
        send(b, gid, "раз")
        eventually(what = "SKDM and message wait for C") {
            node.mailbox(c.me.userId).count { author(it).contentEquals(b.me.userId) } >= 2
        }
        val fromB = node.mailbox(c.me.userId).filter { author(it).contentEquals(b.me.userId) }
        val (group, skdm) = fromB.partition { isGroup(it) }
        val s = node.connect().also { it.login(c.me.userId, c.me.accountKey) }
        c.receiver.handle(s, group)
        assertTrue(c.texts(gid).isEmpty())
        assertEquals(1, c.db.groupDao().pendingEnvelopesFrom(b.me.userId).size)
        c.receiver.handle(s, skdm)
        assertEquals(listOf("раз"), c.texts(gid))
        assertTrue(c.db.groupDao().pendingEnvelopesFrom(b.me.userId).isEmpty())
        c.receiver.handle(s, group + skdm) // redelivery changes nothing
        assertEquals(listOf("раз"), c.texts(gid))
        assertNotNull(c.conv(gid))
        s.close()
    }
}
