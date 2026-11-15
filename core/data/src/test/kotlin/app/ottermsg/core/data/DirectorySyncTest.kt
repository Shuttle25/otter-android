package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.directory.Membership
import app.ottermsg.core.database.entity.ManifestEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
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
import java.security.MessageDigest

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
    private fun acc(seed: Byte) = SeedKeys.accountKeyPair(ByteArray(32) { seed })
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun compare(a: ByteArray, b: ByteArray) = Membership.compareUnsigned(a, b)
    private val networkId get() = Ids.id16(node.network.publicKey)

    private fun invite(signer: Ed25519.KeyPair, author: ByteArray, secret: ByteArray, createdMs: Long = 1): Record {
        val inv = Invite.newBuilder().setInviteId(bs(sha256(secret))).setExpiresMs(Long.MAX_VALUE / 2)
            .setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey)).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.INVITE, author, inv.toByteArray(), createdMs = createdMs), signer)
    }
    private fun chained(seed: Byte, name: String, inv: Record, secret: ByteArray, copyFrom: Record? = null,
                        registeredMs: Long = 1000, version: Long = 1, identitySeed: Byte = seed): Record {
        val acc = acc(seed)
        val id = IdentityKeys.fromSeed(ByteArray(32) { identitySeed })
        val uid = Ids.id16(acc.publicKey)
        val b = DirectoryEntry.newBuilder().setUserId(bs(uid)).setUsername(name).setDisplayName(name.uppercase())
            .setAccountPub(bs(acc.publicKey)).setIdentityPub(bs(id.publicKey.serialize()))
            .setIdentitySig(bs(IdentityKeys.bindAccount(id, acc.publicKey))).setVersion(version).setRegisteredMs(registeredMs)
        if (copyFrom != null) {
            val src = DirectoryEntry.parseFrom(RecordBody.parseFrom(copyFrom.body).payload)
            b.setInviteRecord(src.inviteRecord).setInviteProof(src.inviteProof)
        } else {
            b.setInviteRecord(inv.toByteString()).setInviteProof(bs(InviteKeys.redeemKey(secret)
                .sign(InviteKeys.redeemMessage(sha256(secret), uid, acc.publicKey))))
        }
        return RecordCodec.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, uid, b.build().toByteArray()), acc)
    }
    /** An entry invited by the network key (a bootstrap link). */
    private fun member(seed: Byte, name: String): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return chained(seed, name, invite(node.network, networkId, s), s)
    }
    /** An entry invited by the member [by] (seed) with an invite created at [createdMs]. */
    private fun invitedBy(by: Byte, seed: Byte, name: String, createdMs: Long = 10): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return chained(seed, name, invite(acc(by), uid(by), s, createdMs), s)
    }

    @Test fun verifiesAndFlagsIdentityChanges() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val tampered = entry(3, "tamper").toBuilder().apply { signature = bs(ByteArray(64)) }.build()
        g.directory.apply(listOf(member(2, "anna"), tampered, entry(4, "badbind", badBinding = true), node.directoryRecords().first()))
        val contacts = g.db.contactDao()
        assertEquals("ANNA", contacts.get(uid(2))!!.displayName)
        assertNull(contacts.get(uid(3)))
        assertNull(contacts.get(uid(4)))
        assertNull(contacts.get(g.me.userId)) // self skipped
        assertFalse(contacts.get(uid(2))!!.identityChanged)

        // anna re-registers her identity (version 2): flagged, never verified
        g.directory.apply(listOf(entry(2, "anna", version = 2, identitySeed = 22))) // a known contact: no chain needed
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

    @Test fun hostileDirectoryCannotAddMembers() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val s1 = ByteArray(32) { 1 }
        val good = chained(2, "anna", invite(node.network, networkId, s1), s1)
        val bare = entry(3, "mallory")                                        // no chain
        val copied = chained(4, "mallory2", invite(node.network, networkId, s1), s1, copyFrom = good)
        val rogue = Ed25519.keyPairFromSeed(ByteArray(32) { 77 })            // a node's key pretending to be the network
        val s2 = ByteArray(32) { 2 }
        val forged = chained(5, "mallory3", invite(rogue, networkId, s2), s2)
        val s3 = ByteArray(32) { 3 }
        val early = chained(6, "mallory4", invite(node.network, networkId, s3, createdMs = 50), s3, registeredMs = 40) // before the invite
        val s4 = ByteArray(32) { 4 }
        val strangerInvite = chained(7, "mallory5", invite(acc(60), uid(60), s4), s4) // inviter unknown to us
        g.directory.apply(listOf(bare, copied, forged, early, strangerInvite, good))
        val c = g.db.contactDao()
        assertNotNull(c.get(uid(2)))
        for (n in listOf<Byte>(3, 4, 5, 6, 7)) assertNull("seed $n", c.get(uid(n)))
    }

    @Test fun foundingMembersAndChainsThroughTheBatchAreAccepted() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val founder = entry(3, "founder")
        g.directory.apply(listOf(founder))
        assertNull(g.db.contactDao().get(uid(3))) // not in the manifest yet
        // a manifest signed by another key listing it changes nothing
        val rogue = Ed25519.keyPairFromSeed(ByteArray(32) { 77 })
        val fake = app.ottermsg.proto.v1.Manifest.newBuilder().setVersion(5).setNetworkPub(bs(node.network.publicKey))
            .addFoundingMembers(bs(uid(3))).build()
        g.db.manifestDao().insert(ManifestEntity(5, RecordCodec.sign(RecordCodec.body(RecordType.MANIFEST, networkId, fake.toByteArray()), rogue).toByteArray()))
        g.directory.apply(listOf(founder))
        assertNull(g.db.contactDao().get(uid(3)))
        g.db.manifestDao().insert(ManifestEntity(6, node.signManifest(6, foundingMembers = listOf(uid(3))).toByteArray()))
        // the founder's invitee comes first in the batch: accepted once its inviter is
        g.directory.apply(listOf(invitedBy(3, 4, "dora"), founder))
        assertNotNull(g.db.contactDao().get(uid(3)))
        assertNotNull(g.db.contactDao().get(uid(4)))
    }

    @Test fun inviterRightsAndRevocationAreJudgedAtTheInvite() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val rogue = Ed25519.keyPairFromSeed(ByteArray(32) { 77 })
        node.inviteRights(uid(2), canInvite = false, version = 1, createdMs = 5)  // anna lost her rights before her invite
        node.inviteRights(uid(3), canInvite = false, version = 1, createdMs = 20) // carl after his
        node.inviteRights(uid(8), canInvite = false, version = 1, createdMs = 5, signer = rogue) // forged: ignored
        node.inviteRights(uid(9), canInvite = false, version = 1, createdMs = 5)  // ida: withdrawn, then restored
        node.inviteRights(uid(9), canInvite = true, version = 2, createdMs = 6)
        node.inviteRights(uid(30), canInvite = true, version = 1, createdMs = 5)  // a tie: "no" wins
        node.inviteRights(uid(30), canInvite = false, version = 1, createdMs = 5)
        node.revoke(uid(5), createdMs = 5)                                        // erin revoked before her invite
        node.revoke(uid(6), createdMs = 20)                                       // gus after his
        val inviters = listOf<Byte>(2, 3, 5, 6, 8, 9, 30).map { member(it, "m$it") }
        val invitees = listOf(2 to 12, 3 to 13, 5 to 15, 6 to 16, 8 to 18, 9 to 19, 30 to 31)
            .map { (by, seed) -> invitedBy(by.toByte(), seed.toByte(), "i$seed") }
        g.directory.apply(invitees + inviters + node.directoryRecords())
        val c = g.db.contactDao()
        assertNull(c.get(uid(12))); assertNotNull(c.get(uid(13)))
        assertNull(c.get(uid(15))); assertNotNull(c.get(uid(16)))
        assertNotNull(c.get(uid(18))); assertNotNull(c.get(uid(19))); assertNull(c.get(uid(31)))
        assertTrue(c.get(uid(5))!!.revoked); assertTrue(c.get(uid(6))!!.revoked)
    }

    @Test fun revocationAndDuplicateInvite() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val s = ByteArray(32) { 6 }
        val myInvite = invite(g.me.accountKey, g.me.userId, s) // we invited both: one link used twice
        val x = chained(7, "xenia", myInvite, s)
        val y = chained(8, "yuri", myInvite, s)
        g.directory.apply(listOf(x, y))
        val proofHash = { r: Record -> sha256(DirectoryEntry.parseFrom(RecordBody.parseFrom(r.body).payload).inviteProof.toByteArray()) }
        val loser = if (compare(proofHash(x), proofHash(y)) < 0) uid(8) else uid(7)
        val winner = if (loser.contentEquals(uid(7))) uid(8) else uid(7)
        val c = g.db.contactDao()
        assertTrue(c.get(loser)!!.revoked)
        assertFalse(c.get(winner)!!.revoked)
        val cid = g.db.conversationDao().byPeer(loser)!!.id
        assertTrue(g.db.messageDao().observe(cid).first().any { it.kind == MessageKind.SYSTEM_MEMBER && String(it.systemData!!) == "duplicate_invite" })
        assertNull(g.db.conversationDao().byPeer(winner)) // nothing to tell about the winner

        node.revoke(winner, signer = Ed25519.keyPairFromSeed(ByteArray(32) { 77 })) // forged: ignored
        g.directory.apply(node.directoryRecords() + x + y)
        assertFalse(c.get(winner)!!.revoked)
        node.revoke(winner)
        g.directory.apply(node.directoryRecords() + x + y)
        assertTrue(c.get(winner)!!.revoked)
        g.directory.apply(listOf(x, y)) // a later sync without the revocation does not un-revoke
        assertTrue(c.get(winner)!!.revoked)
    }

    @Test fun aDuplicateLoserNeverInvites() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val s = ByteArray(32) { 6 }
        val shared = invite(node.network, networkId, s) // someone else's link, used twice
        val x = chained(7, "xenia", shared, s)
        val y = chained(8, "yuri", shared, s)
        val proofHash = { r: Record -> sha256(DirectoryEntry.parseFrom(RecordBody.parseFrom(r.body).payload).inviteProof.toByteArray()) }
        val loserSeed: Byte = if (compare(proofHash(x), proofHash(y)) < 0) 8 else 7
        val loserEntry = if (loserSeed == 8.toByte()) y else x
        val z = invitedBy(loserSeed, 20, "zoe")
        g.directory.apply(listOf(z, x, y))
        val c = g.db.contactDao()
        assertTrue(c.get(uid(loserSeed))!!.revoked)
        assertNull(c.get(uid(20)))
        assertNull(g.db.conversationDao().byPeer(uid(loserSeed))) // not our invite: no notice, no chat
        // a node hiding the winner later cannot make the loser an inviter again
        g.directory.apply(listOf(z, loserEntry))
        assertTrue(c.get(uid(loserSeed))!!.revoked)
        assertNull(c.get(uid(20)))
    }

    @Test fun aRevokedContactGetsANoticeInItsChat() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna"), member(3, "boris")))
        val cid = g.db.conversationDao().getOrCreateDirect(uid(2), "ANNA", clock.now())
        node.revoke(uid(2)); node.revoke(uid(3))
        g.directory.apply(node.directoryRecords()) // entries not in the batch: the revocations still apply
        val c = g.db.contactDao()
        assertTrue(c.get(uid(2))!!.revoked); assertTrue(c.get(uid(3))!!.revoked)
        val lines = g.db.messageDao().observe(cid).first().filter { it.kind == MessageKind.SYSTEM_MEMBER }
        assertEquals(listOf("revoked"), lines.map { String(it.systemData!!) })
        assertNull(g.db.conversationDao().byPeer(uid(3))) // no chat with boris: none is created for the notice
        g.directory.apply(node.directoryRecords() + member(2, "anna"))
        assertEquals(1, g.db.messageDao().observe(cid).first().count { it.kind == MessageKind.SYSTEM_MEMBER }) // once
    }
}
