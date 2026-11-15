package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectorySyncTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)

    @After fun tearDown() = g.close()

    private fun entry(seed: Byte, name: String, version: Long = 1, identitySeed: Byte = seed, badBinding: Boolean = false): Record {
        val acc = SeedKeys.accountKeyPair(ByteArray(32) { seed })
        val id = IdentityKeys.fromSeed(ByteArray(32) { identitySeed })
        val sig = IdentityKeys.bindAccount(if (badBinding) IdentityKeys.fromSeed(ByteArray(32) { 99 }) else id, acc.publicKey)
        val e = DirectoryEntry.newBuilder().setUserId(bs(Ids.id16(acc.publicKey))).setUsername(name).setDisplayName(name.uppercase())
            .setAccountPub(bs(acc.publicKey)).setIdentityPub(bs(id.publicKey.serialize())).setIdentitySig(bs(sig))
            .setVersion(version).setCanInvite(true).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, Ids.id16(acc.publicKey), e.toByteArray()), acc)
    }
    private fun uid(seed: Byte) = Ids.id16(SeedKeys.accountKeyPair(ByteArray(32) { seed }).publicKey)

    @Test fun verifiesAndFlagsIdentityChanges() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val tampered = entry(3, "tamper").toBuilder().apply { signature = bs(ByteArray(64)) }.build()
        g.directory.apply(listOf(entry(2, "anna"), tampered, entry(4, "badbind", badBinding = true), node.directoryRecords().first()))
        val contacts = g.db.contactDao()
        assertEquals("ANNA", contacts.get(uid(2))!!.displayName)
        assertNull(contacts.get(uid(3)))
        assertNull(contacts.get(uid(4)))
        assertNull(contacts.get(g.me.userId)) // self skipped
        assertFalse(contacts.get(uid(2))!!.identityChanged)

        // anna re-registers her identity (version 2): flagged, never verified
        g.directory.apply(listOf(entry(2, "anna", version = 2, identitySeed = 22)))
        assertTrue(contacts.get(uid(2))!!.identityChanged)
        assertFalse(contacts.get(uid(2))!!.verified)
        // an older entry does not roll the contact back
        g.directory.apply(listOf(entry(2, "anna_old", version = 1)))
        assertEquals("anna", contacts.get(uid(2))!!.username)
    }

    @Test fun inviterIsConfirmedByTheDirectoryNeverVerifiedByTheLink() = runBlocking {
        val inviter = TestGraph(node, clock)
        try {
            inviter.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
            inviter.online()
            g.register(inviter.invites.create().link, "Я", "me_user").getOrThrow()
            g.directory.apply(node.directoryRecords())
            val c = g.db.contactDao().get(inviter.me.userId)!!
            assertFalse(c.verified) // C23: a link never makes a contact verified
            assertTrue(c.entryVersion > 0)
            assertEquals("mama", c.username) // filled in by the directory
            assertFalse(c.identityChanged)
        } finally { inviter.close() }
    }

    @Test fun linkIdentityThatDiffersFromTheDirectoryIsFlagged() = runBlocking {
        val inviter = TestGraph(node, clock)
        try {
            inviter.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
            inviter.online()
            // A tampered link: the inviter's real account key, someone else's identity key (unsigned link field).
            val real = app.ottermsg.core.crypto.InviteLink.decode(inviter.invites.create().link)
            val evil = IdentityKeys.fromSeed(ByteArray(32) { 77 }).publicKey.serialize()
            g.register(app.ottermsg.core.crypto.InviteLink.encode(real.toBuilder().setInviterIdentityPub(bs(evil)).build()),
                "Я", "me_user").getOrThrow()
            assertEquals(0L, g.db.contactDao().get(inviter.me.userId)!!.entryVersion)
            g.directory.apply(node.directoryRecords())
            val c = g.db.contactDao().get(inviter.me.userId)!!
            assertTrue(c.identityChanged)
            assertFalse(c.verified)
            assertFalse(c.identityPub.contentEquals(evil)) // the directory's key replaced the link's
        } finally { inviter.close() }
    }
}
