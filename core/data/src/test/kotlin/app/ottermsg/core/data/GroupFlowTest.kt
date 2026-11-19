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
import org.junit.Assert.assertArrayEquals
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
        // M9.5 (D135): A and each invitee know each other; siblings learn each other from a group (fetched by id)
        eventually(what = "inviter and invitees know each other") {
            gs.drop(1).all { y -> a.db.contactDao().get(y.me.userId) != null && y.db.contactDao().get(a.me.userId)?.entryVersion?.let { it > 0 } == true }
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

    private suspend fun TestGraph.ids(gid: ByteArray) = db.groupDao().members(gid).map { it.userId.toList() }.toSet()
    private suspend fun TestGraph.adminIds(gid: ByteArray) = db.groupDao().members(gid).filter { it.admin }.map { it.userId.toList() }.toSet()
    private fun idsOf(vararg gs: TestGraph) = gs.map { it.me.userId.toList() }.toSet()
    private suspend fun TestGraph.keyChanged(gid: ByteArray) = msgs(gid).filter { it.kind == MessageKind.SYSTEM_KEY_CHANGED }
    private suspend fun TestGraph.learnDirectory() { directory.sync(session!!); groupRevocations.handle() }
    private suspend fun signerOf(g: TestGraph, gid: ByteArray) =
        app.ottermsg.proto.v1.SignedGroupState.parseFrom(g.db.groupDao().get(gid)!!.stateSigned).signer.toByteArray()

    /** D112: C lost its phone and was re-keyed to D; the admin swaps C for D, nobody else signs, nothing reaches C. */
    @Test fun reKeyedMemberIsSwappedByTheAdmin() = runBlocking {
        family(a, b, c, d)
        val gid = create("Дом", false, b, c)
        for (g in listOf(b, c)) eventually(what = "v1") { g.version(gid) == 1L }
        send(a, gid, "до")
        for (g in listOf(b, c)) eventually(what = "members read") { "до" in g.texts(gid) }
        assertNotNull(a.db.groupDao().myDistribution(gid))
        c.goOffline()
        val cMail = node.mailbox(c.me.userId).size
        node.rekey(c.me.userId, d.me.userId)

        b.learnDirectory() // a plain member: not its job while an admin is there
        assertEquals(1L, b.version(gid))
        a.learnDirectory()
        for (g in listOf(a, b, d)) eventually(what = "the swap reaches everyone") { g.version(gid) == 2L }
        for (g in listOf(a, b, d)) {
            assertEquals(idsOf(a, b, d), g.ids(gid))
            assertEquals(idsOf(a), g.adminIds(gid))
            assertArrayEquals(a.me.userId, signerOf(g, gid))
        }
        for (g in listOf(a, b)) {
            assertEquals("no removed/added events", listOf(GroupEvent.Type.CREATED), g.events(gid))
            val lines = g.keyChanged(gid)
            assertEquals(1, lines.size)
            assertArrayEquals(d.me.userId, lines.single().senderId)
        }
        assertTrue(d.db.groupDao().get(gid)!!.iAmMember)
        assertNull("our sender key rotated", a.db.groupDao().myDistribution(gid))
        assertEquals("no group update to the lost phone", cMail, node.mailbox(c.me.userId).size)

        send(b, gid, "после")
        for (g in listOf(a, d)) eventually(what = "the new account reads") { "после" in g.texts(gid) }
        assertEquals(cMail, node.mailbox(c.me.userId).size)
        for (g in listOf(a, b)) g.learnDirectory()
        assertEquals("swapped once", 2L, a.version(gid))
    }

    /**
     * D112: C, the only admin, was re-keyed to D: the remaining member with the lowest id swaps. A member that does not
     * know the REKEY yet parks the swap and accepts it once it does; the new account learns the group the same way.
     * Hostile: a swap to an account no REKEY names, or signed by a non-member, is never accepted.
     */
    @Test fun soleAdminIsSwappedByTheLowestMember() = runBlocking {
        family(a, b, c, d)
        c.meet(b) // M9.5: siblings; C looks B up (@username) before putting B in a group
        val gid = checkNotNull(c.db.conversationDao().get(c.groups.create("Дача", false, listOf(a.me.userId, b.me.userId)))!!.groupId)
        for (g in listOf(a, b)) eventually(what = "v1") { g.version(gid) == 1L }
        c.goOffline()
        node.rekey(c.me.userId, d.me.userId)
        val (x, y) = listOf(a, b).sortedWith { p, q -> app.ottermsg.core.crypto.GroupRules.compareUnsigned(p.me.userId, q.me.userId) }
        val v1 = checkNotNull(x.applier.current(gid)).state
        fun swap(to: ByteArray) = v1.toBuilder().setVersion(2).clearMembers()
            .addAllMembers(v1.membersList.map { if (it == bs(c.me.userId)) bs(to) else it })
            .clearAdmins().addAdmins(bs(to)).build()

        // hostile: y swaps C for an account of its own choosing (no REKEY names it): parked, never accepted
        val mine = ByteArray(16) { 0x77 }
        val forged = app.ottermsg.core.crypto.GroupSigning.sign(swap(mine), y.me.accountKey)
        assertTrue(x.applier.apply(forged, y.me.userId).verdict is app.ottermsg.core.crypto.GroupRules.Verdict.Park)
        // hostile: the new account signs its own swap before anyone knows it as a member: never accepted
        val byNew = app.ottermsg.core.crypto.GroupSigning.sign(swap(d.me.userId), d.me.accountKey)
        assertTrue(y.applier.apply(byNew, d.me.userId).verdict !is app.ottermsg.core.crypto.GroupRules.Verdict.Accept)
        assertEquals(1L, x.version(gid)); assertEquals(1L, y.version(gid))

        y.learnDirectory() // not the designated member: nothing
        assertEquals(1L, y.version(gid))
        x.learnDirectory()
        assertEquals(2L, x.version(gid))
        assertArrayEquals(x.me.userId, signerOf(x, gid))
        // d has not synced the REKEY: the swap of a non-admin waits as "unknown group"
        eventually(what = "d parked the swap") { d.db.groupDao().pendingUpdates(gid).isNotEmpty() }
        assertNull(d.version(gid))
        d.learnDirectory()
        eventually(what = "d accepted after its REKEY") { d.version(gid) == 2L }
        for (g in listOf(x, y, d)) {
            eventually(what = "everyone has the swap") { g.version(gid) == 2L }
            assertEquals(idsOf(a, b, d), g.ids(gid))
            assertEquals("the admin flag moved with the account", idsOf(d), g.adminIds(gid))
            assertArrayEquals(x.me.userId, signerOf(g, gid))
        }
        assertTrue("forged and early swaps are gone", x.db.groupDao().pendingUpdates(gid).isEmpty() && y.db.groupDao().pendingUpdates(gid).isEmpty())
        for (g in listOf(x, y)) {
            assertEquals(1, g.keyChanged(gid).size)
            assertArrayEquals(d.me.userId, g.keyChanged(gid).single().senderId)
        }
        // the new admin can act and write
        d.groups.rename(gid, "Дача 2")
        for (g in listOf(x, y)) eventually(what = "rename by the new admin") { g.version(gid) == 3L }
        send(d, gid, "снова я")
        for (g in listOf(x, y)) eventually(what = "members read the new account") { "снова я" in g.texts(gid) }
    }
}
