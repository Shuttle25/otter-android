package app.ottermsg.core.data

import app.ottermsg.core.data.directory.Membership
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.proto.v1.SenderKeyDistribution
import app.ottermsg.proto.v1.SignedGroupState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** D104 (C2, groups): a revoked member stops receiving group messages; the lowest non-revoked admin removes it. */
@RunWith(RobolectricTestRunner::class)
class GroupRevocationTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)

    private suspend fun people(vararg gs: TestGraph) {
        val names = listOf("Админ" to "admin1", "Боб" to "bob1", "Ева" to "eve1", "Карл" to "carl1")
        gs.forEachIndexed { i, g ->
            g.register(node.bootstrapLink(), names[i].first, names[i].second).getOrThrow()
            g.online()
        }
        for (g in gs) g.directory.sync(g.session!!)
        for (g in gs) g.meet(*gs) // M9.5: unrelated (all bootstrap-invited), so they look each other up
        eventually(what = "everyone knows everyone") {
            gs.all { x -> gs.all { y -> x === y || x.db.contactDao().get(y.me.userId) != null } }
        }
    }

    private suspend fun TestGraph.conv(gid: ByteArray) = db.conversationDao().byGroup(gid)!!.id
    private suspend fun TestGraph.texts(gid: ByteArray) =
        db.messageDao().observe(conv(gid)).first().filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun TestGraph.lists(gid: ByteArray, who: TestGraph) =
        db.groupDao().members(gid).any { it.userId.contentEquals(who.me.userId) }
    private suspend fun TestGraph.dist(gid: ByteArray) = db.groupDao().myDistribution(gid)?.distributionId
    private suspend fun TestGraph.goOffline() {
        eventually(what = "mailbox drained") { node.mailbox(me.userId).isEmpty() }
        offline()
    }

    @Test fun revokedMemberIsDroppedAndKeysRotate() = runBlocking {
        val admin = TestGraph(node, clock); var bob = TestGraph(node, clock); val eve = TestGraph(node, clock)
        try {
            people(admin, bob, eve)
            val gid = checkNotNull(admin.db.conversationDao().get(
                admin.groups.create("Семья", family = true, members = listOf(bob.me.userId, eve.me.userId)))!!.groupId)
            eventually(what = "group reaches members") { bob.db.groupDao().get(gid) != null && eve.db.groupDao().get(gid) != null }
            bob.messages.sendText(bob.conv(gid), "до")
            eventually(what = "eve reads") { "до" in eve.texts(gid) }
            val before = checkNotNull(bob.dist(gid))
            assertEquals(2, bob.db.groupDao().sharedWith(gid, before).size)

            eve.goOffline()
            node.revoke(eve.me.userId)
            val eveMail = node.mailbox(eve.me.userId).size
            // bob (plain member) learns first: rotates, the admin has not removed eve yet
            bob.directory.sync(bob.session!!); bob.groupRevocations.handle()
            assertNull(bob.dist(gid))
            assertTrue(bob.db.groupDao().sharedWith(gid, before).isEmpty())
            assertTrue("the signed state still lists eve", bob.lists(gid, eve))
            assertEquals(1L, admin.db.groupDao().get(gid)!!.version)

            bob.messages.sendText(bob.conv(gid), "после")
            eventually(what = "admin reads") { "после" in admin.texts(gid) }
            val after = checkNotNull(bob.dist(gid))
            assertTrue(!after.contentEquals(before))
            assertEquals(listOf(admin.me.userId.toList()), bob.db.groupDao().sharedWith(gid, after).map { it.toList() })

            // handled once, also across a restart: no second rotation while the state still lists eve
            bob = bob.restart(); bob.online()
            bob.groupRevocations.handle()
            assertArrayEquals(after, bob.dist(gid))

            // the admin learns on its next login (SyncEngine: directory sync → handle) and removes eve
            admin.online()
            eventually(what = "admin removed eve") { !admin.lists(gid, eve) }
            assertEquals(2L, admin.db.groupDao().get(gid)!!.version)
            eventually(what = "bob applied the removal") { !bob.lists(gid, eve) }
            val v = admin.db.groupDao().get(gid)!!.version
            admin.groupRevocations.handle()
            assertEquals(v, admin.db.groupDao().get(gid)!!.version)

            // nothing new for eve: no SKDM, no group message, no group update
            bob.messages.sendText(bob.conv(gid), "ещё")
            eventually(what = "admin reads again") { "ещё" in admin.texts(gid) }
            assertEquals(eveMail, node.mailbox(eve.me.userId).size)
        } finally { admin.close(); bob.close(); eve.close() }
    }

    @Test fun onlyTheLowestNonRevokedAdminRemoves() = runBlocking {
        val a = TestGraph(node, clock); val b = TestGraph(node, clock); val e = TestGraph(node, clock); val c = TestGraph(node, clock)
        val all = listOf(a, b, e, c)
        try {
            people(a, b, e, c)
            val gid = checkNotNull(a.db.conversationDao().get(
                a.groups.create("Дом", family = false, members = listOf(b.me.userId, e.me.userId, c.me.userId)))!!.groupId)
            a.groups.setAdmin(gid, b.me.userId, true)
            a.groups.setAdmin(gid, e.me.userId, true)
            for (g in all) eventually(what = "admins reach everyone") { g.db.groupDao().get(gid)?.version == 3L }
            e.goOffline()
            node.revoke(e.me.userId)
            val eMail = node.mailbox(e.me.userId).size
            val designated = listOf(a, b).minWithOrNull { x, y -> Membership.compareUnsigned(x.me.userId, y.me.userId) }!!
            for (g in listOf(c, b, a)) { g.directory.sync(g.session!!); g.groupRevocations.handle() }
            for (g in listOf(a, b, c)) eventually(what = "everyone applied the removal") { !g.lists(gid, e) }
            for (g in listOf(a, b, c)) {
                val s = g.db.groupDao().get(gid)!!
                assertEquals("exactly one removal", 4L, s.version)
                assertArrayEquals(designated.me.userId, SignedGroupState.parseFrom(s.stateSigned).signer.toByteArray())
            }
            assertEquals("nothing reaches the removed member", eMail, node.mailbox(e.me.userId).size)
        } finally { all.forEach { it.close() } }
    }

    @Test fun senderKeyFromRevokedMemberIsDropped() = runBlocking {
        val a = TestGraph(node, clock); val e = TestGraph(node, clock)
        try {
            people(a, e)
            e.offline()
            node.revoke(e.me.userId)
            a.directory.sync(a.session!!)
            assertTrue(a.db.contactDao().get(e.me.userId)!!.revoked)
            val dist = UUID.randomUUID()
            val skdm = e.signal.withGroups { it.distribution(dist) }
            val ct = e.signal.withGroups { it.encrypt(dist, "тайна".toByteArray()) }
            a.groupReceiver.onSenderKey(e.me.userId, SenderKeyDistribution.newBuilder().setGroupId(bs(ByteArray(16)))
                .setDistributionId(bs(app.ottermsg.core.data.messaging.GroupSender.uuidBytes(dist))).setMessage(bs(skdm)).build())
            try {
                a.signal.withGroups { g -> g.decrypt(g.address(e.me.userId), ct) }
                fail("the revoked sender's key was stored")
            } catch (x: Exception) { assertNotNull(x) }
        } finally { a.close(); e.close() }
    }

    /** D112: step 1 of a re-key (REVOCATION reason "rekey") rotates keys but leaves the member for the swap. */
    @Test fun rekeyRevocationWaitsForTheSwap() = runBlocking {
        val admin = TestGraph(node, clock); val bob = TestGraph(node, clock); val eve = TestGraph(node, clock)
        val eve2 = TestGraph(node, clock)
        try {
            people(admin, bob, eve, eve2)
            val gid = checkNotNull(admin.db.conversationDao().get(
                admin.groups.create("Семья", family = true, members = listOf(bob.me.userId, eve.me.userId)))!!.groupId)
            eventually(what = "group reaches members") { bob.db.groupDao().get(gid) != null && eve.db.groupDao().get(gid) != null }
            admin.messages.sendText(admin.conv(gid), "до")
            eventually(what = "eve reads") { "до" in eve.texts(gid) }
            eve.goOffline()
            node.revoke(eve.me.userId, reason = "rekey")
            for (g in listOf(bob, admin)) { g.directory.sync(g.session!!); g.groupRevocations.handle() }
            assertNull("keys rotate at once", admin.dist(gid))
            assertTrue("not removed while the REKEY is due", admin.lists(gid, eve))
            assertEquals(1L, admin.db.groupDao().get(gid)!!.version)
            admin.messages.sendText(admin.conv(gid), "между")
            eventually(what = "bob reads") { "между" in bob.texts(gid) }
            val rotated = checkNotNull(admin.dist(gid))
            admin.groupRevocations.handle()
            assertArrayEquals("rotated once", rotated, admin.dist(gid))

            node.rekey(eve.me.userId, eve2.me.userId)
            for (g in listOf(bob, admin)) { g.directory.sync(g.session!!); g.groupRevocations.handle() }
            for (g in listOf(admin, bob, eve2)) eventually(what = "swapped, not removed") { g.db.groupDao().get(gid)?.version == 2L }
            for (g in listOf(admin, bob)) {
                assertTrue(g.lists(gid, eve2)); assertTrue(!g.lists(gid, eve))
                g.groupRevocations.handle()
                assertEquals("exactly one change", 2L, g.db.groupDao().get(gid)!!.version)
            }
        } finally { admin.close(); bob.close(); eve.close(); eve2.close() }
    }

    /** A re-key that is never completed: after the wait the member is removed like any revoked member. */
    @Test fun rekeyRevocationWithoutRekeyEndsInRemoval() = runBlocking {
        val admin = TestGraph(node, clock); val eve = TestGraph(node, clock)
        try {
            people(admin, eve)
            val gid = checkNotNull(admin.db.conversationDao().get(
                admin.groups.create("Семья", family = true, members = listOf(eve.me.userId)))!!.groupId)
            eventually(what = "group reaches eve") { eve.db.groupDao().get(gid) != null }
            eve.goOffline()
            node.revoke(eve.me.userId, reason = "rekey")
            admin.directory.sync(admin.session!!); admin.groupRevocations.handle()
            assertTrue(admin.lists(gid, eve))
            clock.advance(app.ottermsg.core.data.group.GroupRevocations.REKEY_WAIT_MS + 1)
            admin.groupRevocations.handle()
            assertTrue(!admin.lists(gid, eve))
            assertEquals(2L, admin.db.groupDao().get(gid)!!.version)
        } finally { admin.close(); eve.close() }
    }
}
