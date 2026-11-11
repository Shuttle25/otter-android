package app.ottermsg.core.crypto

import app.ottermsg.core.crypto.GroupRules.Applied
import app.ottermsg.core.crypto.GroupRules.Verdict
import app.ottermsg.proto.v1.GroupState
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRulesTest {
    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private val a = id(1); private val b = id(2); private val c = id(3); private val d = id(4)
    private val gid = ByteArray(16) { 9 }

    private fun state(version: Long, members: List<ByteArray>, admins: List<ByteArray>, name: String = "Семья",
                      family: Boolean = false, createdBy: ByteArray = a): GroupState =
        GroupState.newBuilder().setGroupId(ByteString.copyFrom(gid)).setName(name).setVersion(version).setFamily(family)
            .setCreatedBy(ByteString.copyFrom(createdBy)).setCreatedMs(1000)
            .addAllMembers(members.map { ByteString.copyFrom(it) }).addAllAdmins(admins.map { ByteString.copyFrom(it) }).build()

    private val v1 = state(1, listOf(a, b, c), listOf(a))
    private val cur = Applied(v1, a)

    private fun eval(current: Applied?, candidate: GroupState, signer: ByteArray, previous: GroupState? = null) =
        GroupRules.evaluate(current, previous, candidate, signer)

    private data class Case(val name: String, val current: Applied?, val candidate: GroupState, val signer: ByteArray, val want: Class<out Verdict>)

    @Test fun table() {
        val cases = listOf(
            Case("create", null, v1, a, Verdict.Accept::class.java),
            Case("create signed by a non-creator", null, v1, b, Verdict.Reject::class.java),
            Case("create where creator is no admin", null, state(1, listOf(a, b), listOf(b)), a, Verdict.Reject::class.java),
            Case("admin adds", cur, state(2, listOf(a, b, c, d), listOf(a)), a, Verdict.Accept::class.java),
            Case("admin removes", cur, state(2, listOf(a, b), listOf(a)), a, Verdict.Accept::class.java),
            Case("admin renames", cur, state(2, listOf(a, b, c), listOf(a), name = "Дача"), a, Verdict.Accept::class.java),
            Case("admin promotes", cur, state(2, listOf(a, b, c), listOf(a, b)), a, Verdict.Accept::class.java),
            Case("non-admin renames", cur, state(2, listOf(a, b, c), listOf(a), name = "X"), b, Verdict.Reject::class.java),
            Case("non-admin adds", cur, state(2, listOf(a, b, c, d), listOf(a)), b, Verdict.Reject::class.java),
            Case("self-leave", cur, state(2, listOf(a, c), listOf(a)), b, Verdict.Accept::class.java),
            Case("leave that renames", cur, state(2, listOf(a, c), listOf(a), name = "X"), b, Verdict.Reject::class.java),
            Case("leave that removes someone else", cur, state(2, listOf(a), listOf(a)), b, Verdict.Reject::class.java),
            Case("last admin leaves while others remain", cur, state(2, listOf(b, c), listOf()), a, Verdict.Reject::class.java),
            Case("admin gap", cur, state(5, listOf(a, b), listOf(a)), a, Verdict.Accept::class.java),
            Case("non-admin gap", cur, state(5, listOf(a, b), listOf(a, b)), b, Verdict.Park::class.java),
            Case("unknown signer next version", cur, state(2, listOf(a, b, c, d), listOf(a, d)), d, Verdict.Park::class.java),
            Case("old version", Applied(state(3, listOf(a, b, c), listOf(a)), a), state(2, listOf(a, b), listOf(a)), a, Verdict.Reject::class.java),
            Case("family flag changed", cur, state(2, listOf(a, b, c), listOf(a), family = true), a, Verdict.Reject::class.java),
            Case("created_by changed", cur, state(2, listOf(a, b, c), listOf(a), createdBy = b), a, Verdict.Reject::class.java),
            Case("101 members", cur, state(2, (1..101).map { n -> ByteArray(16) { i -> if (i == 0) n.toByte() else 7 } }.let { it.take(100) + listOf(a) }, listOf(a)), a, Verdict.Reject::class.java),
            Case("duplicate member", cur, state(2, listOf(a, b, b), listOf(a)), a, Verdict.Reject::class.java),
            Case("admin not a member", cur, state(2, listOf(a, b), listOf(a, d)), a, Verdict.Reject::class.java),
            Case("empty name", cur, state(2, listOf(a, b, c), listOf(a), name = " "), a, Verdict.Reject::class.java),
            Case("name too long", cur, state(2, listOf(a, b, c), listOf(a), name = "я".repeat(65)), a, Verdict.Reject::class.java),
            Case("joiner trusts an admin of the state", null, state(4, listOf(a, b, d), listOf(a)), a, Verdict.Accept::class.java),
            Case("joiner parks a non-admin state", null, state(4, listOf(a, d), listOf(a)), b, Verdict.Park::class.java),
        )
        for (k in cases) assertEquals(k.name, k.want, eval(k.current, k.candidate, k.signer).javaClass)
    }

    @Test fun lastMemberMayLeave() {
        val solo = Applied(state(3, listOf(a), listOf(a)), a)
        assertEquals(Verdict.Accept, eval(solo, state(4, listOf(), listOf()), a))
    }

    @Test fun outOfOrderUpdateParksThenApplies() {
        val v2 = state(2, listOf(a, b, c), listOf(a, b))       // A promotes B
        val v3 = state(3, listOf(a, b, c, d), listOf(a, b))    // B adds D
        assertTrue(eval(cur, v3, b) is Verdict.Park)
        assertEquals(Verdict.Accept, eval(cur, v2, a))
        assertEquals(Verdict.Accept, eval(Applied(v2, a), v3, b, previous = v1))
    }

    @Test fun concurrentEditsConvergeInBothOrders() {
        val v2 = state(2, listOf(a, b, c), listOf(a, b))
        val byA = state(3, listOf(a, b, c), listOf(a, b), name = "X")
        val byB = state(3, listOf(a, b, c), listOf(a, b), name = "Y")
        // order 1: holds A's rename, B's arrives
        val r1 = eval(Applied(byA, a), byB, b, previous = v2)
        // order 2: holds B's rename, A's arrives
        val r2 = eval(Applied(byB, b), byA, a, previous = v2)
        assertTrue(r1 is Verdict.Reject)             // A (lower id) keeps winning
        assertEquals(Verdict.Accept, r2)              // A replaces B
        // equal signer: lower SHA-256 wins, and exactly one side accepts
        val x = state(3, listOf(a, b, c), listOf(a, b), name = "P")
        val y = state(3, listOf(a, b, c), listOf(a, b), name = "Q")
        val xy = eval(Applied(x, a), y, a, previous = v2)
        val yx = eval(Applied(y, a), x, a, previous = v2)
        assertTrue((xy == Verdict.Accept) != (yx == Verdict.Accept))
    }

    @Test fun concurrentStateMustBeValidAgainstPrevious() {
        val v2 = state(2, listOf(a, b, c), listOf(a))
        val held = Applied(state(3, listOf(a, b, c), listOf(a), name = "X"), a)
        val byNonAdmin = state(3, listOf(a, b, c), listOf(a), name = "Y")
        // a lower signer id does not help a state its signer was not allowed to make
        assertTrue(eval(held, byNonAdmin, ByteArray(16), previous = v2) is Verdict.Reject)
        assertTrue(eval(held, held.state, a, previous = v2) is Verdict.Reject) // duplicate
    }

    @Test fun signingRoundTrip() {
        val acct = Ed25519.keyPairFromSeed(ByteArray(32) { 5 })
        val other = Ed25519.keyPairFromSeed(ByteArray(32) { 6 })
        val signed = GroupSigning.sign(v1, acct)
        assertTrue(GroupSigning.verify(signed, acct.publicKey))
        assertFalse(GroupSigning.verify(signed, other.publicKey))
        val tampered = signed.toBuilder().setState(ByteString.copyFrom(state(1, listOf(a), listOf(a)).toByteArray())).build()
        assertFalse(GroupSigning.verify(tampered, acct.publicKey))
        assertEquals(v1, GroupState.parseFrom(signed.state))
    }
}
