package app.ottermsg.core.data

import app.ottermsg.core.crypto.GroupRules.Verdict
import app.ottermsg.core.crypto.GroupSigning
import app.ottermsg.proto.v1.GroupState
import app.ottermsg.proto.v1.SignedGroupState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A group state signed by an account revoked on this phone (REVOCATION, REKEY, duplicate-invite loser) is refused, not
 * parked: whoever holds a revoked admin's lost phone, or a re-keyed member's old key, cannot change a group any more.
 */
@RunWith(RobolectricTestRunner::class)
class GroupRevokedSignerTest {
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

    private suspend fun TestGraph.goOffline() {
        eventually(what = "mailbox drained") { node.mailbox(me.userId).isEmpty() }
        offline()
    }
    private suspend fun TestGraph.lists(gid: ByteArray, who: TestGraph) =
        db.groupDao().members(gid).any { it.userId.contentEquals(who.me.userId) }
    private suspend fun TestGraph.signer(gid: ByteArray) = SignedGroupState.parseFrom(db.groupDao().get(gid)!!.stateSigned).signer.toByteArray()
    private suspend fun TestGraph.state(gid: ByteArray) = GroupState.parseFrom(SignedGroupState.parseFrom(db.groupDao().get(gid)!!.stateSigned).state)

    /** What a hostile holder of [g]'s key signs: [edit] applied to the state [g] holds, at [version]. */
    private suspend fun forge(g: TestGraph, gid: ByteArray, version: Long, edit: (GroupState.Builder) -> Unit): SignedGroupState {
        val b = g.state(gid).toBuilder().setVersion(version).also(edit)
        return GroupSigning.sign(b.build(), g.me.accountKey)
    }

    private suspend fun assertRefused(g: TestGraph, gid: ByteArray, forged: SignedGroupState, from: TestGraph) {
        val before = g.db.groupDao().get(gid)!!.stateSigned
        assertEquals(Verdict.Reject("revoked signer"), g.applier.apply(forged, from.me.userId, null).verdict)
        // also when the phone is online and could look the signer up
        assertEquals(Verdict.Reject("revoked signer"), g.applier.apply(forged, from.me.userId, g.session).verdict)
        assertArrayEquals("state unchanged", before, g.db.groupDao().get(gid)!!.stateSigned)
        assertTrue("not parked", g.db.groupDao().pendingUpdates(gid).isEmpty())
    }

    /** (1) A revoked admin's lost phone signs version+1 removing someone: refused on every other phone. */
    @Test fun revokedAdminCannotRemoveAMember() = runBlocking {
        val a = TestGraph(node, clock); val b = TestGraph(node, clock); val e = TestGraph(node, clock)
        try {
            people(a, b, e)
            val gid = checkNotNull(a.db.conversationDao().get(
                a.groups.create("Семья", family = true, members = listOf(b.me.userId, e.me.userId)))!!.groupId)
            a.groups.setAdmin(gid, e.me.userId, true)
            for (g in listOf(a, b, e)) eventually(what = "e is an admin everywhere") { g.db.groupDao().get(gid)?.version == 2L }
            e.goOffline()
            node.revoke(e.me.userId)
            for (g in listOf(a, b)) g.directory.sync(g.session!!)

            val forged = forge(e, gid, 3) { s ->
                val keep = s.membersList.filterNot { it == bs(b.me.userId) }
                s.clearMembers().addAllMembers(keep)
            }
            for (g in listOf(a, b)) {
                assertRefused(g, gid, forged, e)
                assertTrue(g.lists(gid, b))
            }
            // the same kind of change from an admin who is not revoked still works
            a.groups.rename(gid, "Дом")
            eventually(what = "honest rename reaches b") { b.state(gid).name == "Дом" }
        } finally { a.close(); b.close(); e.close() }
    }

    /** (2) A re-keyed member's old key signs a rename or a swap back: refused (it would have been accepted or parked before). */
    @Test fun reKeyedMembersOldKeyCannotChangeTheGroup() = runBlocking {
        val a = TestGraph(node, clock); val b = TestGraph(node, clock); val eve = TestGraph(node, clock); val eve2 = TestGraph(node, clock)
        try {
            people(a, b, eve, eve2)
            val gid = checkNotNull(a.db.conversationDao().get(
                a.groups.create("Семья", family = true, members = listOf(b.me.userId, eve.me.userId)))!!.groupId)
            a.groups.setAdmin(gid, eve.me.userId, true)
            for (g in listOf(a, b, eve)) eventually(what = "eve is an admin everywhere") { g.db.groupDao().get(gid)?.version == 2L }
            eve.goOffline()
            node.revoke(eve.me.userId, reason = "rekey")
            node.rekey(eve.me.userId, eve2.me.userId)
            for (g in listOf(b, a)) { g.directory.sync(g.session!!); g.groupRevocations.handle() }
            for (g in listOf(a, b, eve2)) eventually(what = "swapped") { g.db.groupDao().get(gid)?.version == 3L }
            eve2.directory.sync(eve2.session!!)

            // eve's old phone still holds v2, where it was an admin
            val renameSameVersion = forge(eve, gid, 3) { it.setName("Чужие") }
            val swapped = a.state(gid)
            val swapBack = GroupSigning.sign(swapped.toBuilder().setVersion(4)
                .clearMembers().addAllMembers(swapped.membersList.map { if (it == bs(eve2.me.userId)) bs(eve.me.userId) else it })
                .clearAdmins().addAllAdmins(swapped.adminsList.map { if (it == bs(eve2.me.userId)) bs(eve.me.userId) else it })
                .build(), eve.me.accountKey)
            val renameLater = GroupSigning.sign(swapped.toBuilder().setVersion(4).setName("Чужие").build(), eve.me.accountKey)
            for (g in listOf(a, b, eve2)) {
                val holder = g.signer(gid)
                for (f in listOf(renameSameVersion, swapBack, renameLater)) assertRefused(g, gid, f, eve)
                assertEquals("Семья", g.state(gid).name)
                assertTrue(g.lists(gid, eve2))
                assertArrayEquals(holder, g.signer(gid))
            }
        } finally { a.close(); b.close(); eve.close(); eve2.close() }
    }

    /**
     * (3) A phone took the revoked admin's state before it learned of the revocation; the honest admin's later state still
     * wins there and everywhere else, so the group converges.
     */
    @Test fun honestAdminsLaterStateWinsEverywhere() = runBlocking {
        val a = TestGraph(node, clock); val b = TestGraph(node, clock); val c = TestGraph(node, clock); val e = TestGraph(node, clock)
        try {
            people(a, b, c, e)
            val gid = checkNotNull(a.db.conversationDao().get(
                a.groups.create("Семья", family = false, members = listOf(b.me.userId, c.me.userId, e.me.userId)))!!.groupId)
            a.groups.setAdmin(gid, e.me.userId, true)
            for (g in listOf(a, b, c, e)) eventually(what = "e is an admin everywhere") { g.db.groupDao().get(gid)?.version == 2L }
            c.goOffline(); e.goOffline()
            node.revoke(e.me.userId)
            for (g in listOf(a, b)) g.directory.sync(g.session!!)

            // e's lost phone removes b and strips a's admin rights
            val forged = forge(e, gid, 3) { s ->
                val keep = s.membersList.filterNot { it == bs(b.me.userId) }
                s.clearMembers().addAllMembers(keep).clearAdmins().addAllAdmins(listOf(bs(e.me.userId)))
            }
            assertEquals("c does not know yet", Verdict.Accept, c.applier.apply(forged, e.me.userId, null).verdict)
            assertTrue(!c.lists(gid, b))
            for (g in listOf(a, b)) assertRefused(g, gid, forged, e)

            a.groups.rename(gid, "Новое")
            eventually(what = "b takes a's state") { b.state(gid).name == "Новое" }
            c.online()
            c.directory.sync(c.session!!)
            c.applier.apply(SignedGroupState.parseFrom(a.db.groupDao().get(gid)!!.stateSigned), a.me.userId, null)
            for (g in listOf(a, b, c)) {
                eventually(what = "converged on a's state") { g.state(gid).name == "Новое" && g.lists(gid, b) }
                assertArrayEquals(a.me.userId, g.signer(gid))
            }

            a.groups.rename(gid, "Ещё")
            val v = a.db.groupDao().get(gid)!!.version
            for (g in listOf(b, c)) eventually(what = "later state everywhere") {
                g.db.groupDao().get(gid)?.version == v && g.state(gid).name == "Ещё"
            }
        } finally { a.close(); b.close(); c.close(); e.close() }
    }
}
