package app.ottermsg.core.data

import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fake node applies the Go service's M9.5 rules (D135, D137), so data tests exercise them. */
class FakeNodeVisibilityTest {
    private val node = FakeNode({ 1_800_000_000_000 })
    private val f = DirectoryFixtures(node)
    private fun names(rs: List<Record>) = rs.map { RecordBody.parseFrom(it.body) }.filter { it.type == RecordType.DIRECTORY_ENTRY }
        .map { DirectoryEntry.parseFrom(it.payload).username }.toSet()

    init {
        node.putDirectoryEntry(f.member(1, "grandma"))          // founding-style, invited by the network
        node.putDirectoryEntry(f.invitedBy(1, 2, "mama"))       // invited by grandma
        node.putDirectoryEntry(f.invitedBy(2, 3, "me_user"))    // invited by mama
        node.putDirectoryEntry(f.invitedBy(3, 4, "kid"))        // my invitee
        node.putDirectoryEntry(f.invitedBy(4, 5, "grandkid"))   // two levels down: not mine
        node.putDirectoryEntry(f.member(6, "stranger"))
        node.putDirectoryEntry(f.member(7, "open", listed = true))
        node.putDirectoryEntry(f.invitedBy(2, 8, "aunt"))       // mama's other invitee: not mine
    }

    @Test fun visibleSetIsOwnListedDirectAndAncestry() {
        node.revoke(f.uid(6))
        val v = node.visibleFor(f.uid(3))
        assertEquals(setOf("me_user", "mama", "grandma", "kid", "open"), names(v))
        assertEquals(1, v.count { RecordBody.parseFrom(it.body).type == RecordType.REVOCATION }) // membership: all of it
    }

    @Test fun getAndLookupCarryTheAncestry() {
        assertEquals(setOf("grandkid", "kid", "me_user", "mama", "grandma"), names(node.withAncestry(setOf(f.uid(5).joinToString("") { "%02x".format(it) }))))
        assertEquals(setOf("aunt", "mama", "grandma"), names(node.lookup("@Aunt")!!))
        assertNull(node.lookup("nobody"))
        node.revoke(f.uid(8))
        assertNull(node.lookup("aunt")) // revoked accounts are not found
    }
}
