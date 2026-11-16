package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.core.data.directory.ContactRepository
import app.ottermsg.core.data.directory.Membership
import app.ottermsg.core.data.group.GroupRevocations
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Rekey
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
import java.security.MessageDigest

/** D111 on the phone: a network-signed REKEY moves the lost account's chat to the new account, as nodes apply it. */
@RunWith(RobolectricTestRunner::class)
class RekeyTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    @After fun tearDown() = g.close()

    private val networkId get() = Ids.id16(node.network.publicKey)
    private fun acc(seed: Byte) = SeedKeys.accountKeyPair(ByteArray(32) { seed })
    private fun uid(seed: Byte) = Ids.id16(acc(seed).publicKey)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** A REKEY that is not served by the node (built for one batch). */
    private fun rekey(old: ByteArray, new: ByteArray, signer: Ed25519.KeyPair = node.network, author: ByteArray = networkId,
                      createdMs: Long = clock.now(), id: ByteArray? = null): Record {
        val p = Rekey.newBuilder().setOldUserId(bs(old)).setNewUserId(bs(new)).build().toByteArray()
        val body = if (id == null) RecordCodec.body(RecordType.REKEY, author, p, createdMs = createdMs)
            else RecordCodec.body(RecordType.REKEY, author, p, id = id, createdMs = createdMs)
        return RecordCodec.sign(body, signer, node.ctx)
    }
    private fun invite(signer: Ed25519.KeyPair, author: ByteArray, secret: ByteArray, createdMs: Long = 1): Record {
        val inv = Invite.newBuilder().setInviteId(bs(sha256(secret))).setExpiresMs(createdMs + Membership.MAX_INVITE_TTL_MS)
            .setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey)).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.INVITE, author, inv.toByteArray(), createdMs = createdMs), signer, node.ctx)
    }
    /** An entry of [seed]; with [inv] it carries that invite chain, without one it has none (only a REKEY admits it). */
    private fun entry(seed: Byte, name: String, inv: Record? = null, secret: ByteArray? = null, registeredMs: Long = 1000): Record {
        val a = acc(seed)
        val id = IdentityKeys.fromSeed(ByteArray(32) { seed })
        val u = Ids.id16(a.publicKey)
        val b = DirectoryEntry.newBuilder().setUserId(bs(u)).setUsername(name).setDisplayName(name.uppercase())
            .setAccountPub(bs(a.publicKey)).setIdentityPub(bs(id.publicKey.serialize()))
            .setIdentitySig(bs(IdentityKeys.bindAccount(id, a.publicKey))).setVersion(1).setRegisteredMs(registeredMs)
        if (inv != null && secret != null) b.setInviteRecord(inv.toByteString())
            .setInviteProof(bs(InviteKeys.redeemKey(secret).sign(InviteKeys.redeemMessage(sha256(secret), u, a.publicKey))))
        return RecordCodec.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, u, b.build().toByteArray()), a, node.ctx)
    }
    private fun member(seed: Byte, name: String): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return entry(seed, name, invite(node.network, networkId, s), s)
    }
    private fun invitedBy(by: Byte, seed: Byte, name: String, createdMs: Long): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return entry(seed, name, invite(acc(by), uid(by), s, createdMs), s)
    }
    private suspend fun keyChangedLines(cid: Long) = g.db.messageDao().forConversation(cid).count { it.kind == MessageKind.SYSTEM_KEY_CHANGED }

    @Test fun chatMovesToTheNewAccount() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val oldId = uid(2); val newId = uid(3)
        g.directory.apply(listOf(member(2, "anna")))
        val cid = g.db.conversationDao().getOrCreateDirect(oldId, "ANNA", clock.now())
        val sent = g.messages.sendText(cid, "привет")!! // queued in the outbox to old (offline)
        g.directory.apply(listOf(member(3, "anna"), rekey(oldId, newId)))

        val contacts = g.db.contactDao()
        assertTrue(contacts.get(oldId)!!.revoked)
        val c = contacts.get(newId)!!
        assertTrue(c.identityChanged)
        assertFalse(c.verified)
        assertFalse(c.revoked)
        assertEquals(cid, g.db.conversationDao().byPeer(newId)!!.id)
        assertNull(g.db.conversationDao().byPeer(oldId))
        val lines = g.db.messageDao().forConversation(cid)
        assertEquals(1, lines.count { it.kind == MessageKind.SYSTEM_KEY_CHANGED })
        assertArrayEquals(newId, lines.single { it.kind == MessageKind.SYSTEM_KEY_CHANGED }.senderId)
        assertEquals(0, lines.count { it.kind == MessageKind.SYSTEM_MEMBER }) // no «removed» line for a re-key
        assertTrue(g.db.outboxDao().all().none { it.recipientUserId.contentEquals(oldId) && it.sentMs == null })
        assertEquals(MessageStatus.FAILED, g.db.messageDao().get(sent)!!.status) // a retry goes to the new account
        assertTrue(g.messages.retry(sent))
        assertTrue(g.db.outboxDao().all().any { it.recipientUserId.contentEquals(newId) && it.messageLocalId == sent })

        g.directory.apply(listOf(rekey(oldId, newId))) // a second REKEY of the same pair changes nothing more
        g.directory.apply(listOf(member(2, "anna"), member(3, "anna"))) // a replayed old entry does not un-revoke
        assertEquals(1, keyChangedLines(cid))
        assertTrue(contacts.get(oldId)!!.revoked)
    }

    @Test fun existingChatWithTheNewAccountIsMerged() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna"), member(3, "anna2")))
        val oldCid = g.db.conversationDao().getOrCreateDirect(uid(2), "ANNA", clock.now())
        val newCid = g.db.conversationDao().getOrCreateDirect(uid(3), "ANNA2", clock.now())
        g.messages.sendText(oldCid, "один")
        node.rekey(uid(2), uid(3)) // served by directory.sync
        g.online()
        g.directory.sync(g.session!!)
        assertNull(g.db.conversationDao().get(newCid))
        assertEquals(oldCid, g.db.conversationDao().byPeer(uid(3))!!.id)
        assertEquals(1, keyChangedLines(oldCid))
    }

    @Test fun newAccountWithoutAnInviteIsAdmittedOnlyByTheRekey() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna")))
        val cid = g.db.conversationDao().getOrCreateDirect(uid(2), "ANNA", clock.now())
        val bare = entry(3, "anna")
        g.directory.apply(listOf(bare))
        assertNull(g.db.contactDao().get(uid(3))) // no chain: not a member
        g.directory.apply(listOf(bare, rekey(uid(2), uid(3))))
        assertNotNull(g.db.contactDao().get(uid(3)))
        assertEquals(cid, g.db.conversationDao().byPeer(uid(3))!!.id)
    }

    @Test fun rekeyWaitsForTheNewEntry() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna")))
        val cid = g.db.conversationDao().getOrCreateDirect(uid(2), "ANNA", clock.now())
        g.directory.apply(listOf(rekey(uid(2), uid(3))))
        assertTrue(g.db.contactDao().get(uid(2))!!.revoked) // revoked at once
        assertEquals(0L, g.db.rekeyDao().get(uid(2))!!.appliedMs)
        assertEquals(cid, g.db.conversationDao().byPeer(uid(2))!!.id) // the chat waits for the new account
        g.directory.apply(listOf(entry(3, "anna"))) // the REKEY is stored: the bare entry is admitted, the chat moves
        assertEquals(cid, g.db.conversationDao().byPeer(uid(3))!!.id)
        assertEquals(1, keyChangedLines(cid))
    }

    @Test fun forgedRekeysAreIgnored() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val oldId = uid(2)
        g.directory.apply(listOf(member(2, "anna")))
        val cid = g.db.conversationDao().getOrCreateDirect(oldId, "ANNA", clock.now())
        val thief = entry(0x66, "anna") // the thief's own account, no invite
        val thiefId = uid(0x66)
        val nodeKey = Ed25519.keyPairFromSeed(ByteArray(32) { 0x42 })
        val otherNet = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
        // a hostile node serves REKEYs signed by the old account, by a node key and by another network
        node.rekey(oldId, thiefId, signer = acc(2), author = oldId)
        node.rekey(oldId, thiefId, signer = acc(2))
        node.rekey(oldId, thiefId, signer = nodeKey, author = Ids.id16(nodeKey.publicKey))
        node.rekey(oldId, thiefId, signer = nodeKey)
        node.rekey(oldId, thiefId, signer = otherNet, author = Ids.id16(otherNet.publicKey), sig = SigContext(Ids.id16(otherNet.publicKey), 1))
        node.rekey(oldId, thiefId, signer = otherNet, sig = SigContext(Ids.id16(otherNet.publicKey), 1))
        node.directoryAnswer = { d -> d + thief }
        g.online()
        g.directory.sync(g.session!!)
        assertNull(g.db.rekeyDao().get(oldId))
        assertTrue(g.db.rekeyDao().all().isEmpty())
        assertFalse(g.db.contactDao().get(oldId)!!.revoked)
        assertNull(g.db.contactDao().get(thiefId))
        assertEquals(cid, g.db.conversationDao().byPeer(oldId)!!.id)
        assertEquals(0, keyChangedLines(cid))
    }

    @Test fun theLaterRekeyWinsInAnyOrder() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna"), member(3, "anna"), member(4, "anna")))
        val early = rekey(uid(2), uid(3), createdMs = 100)
        val late = rekey(uid(2), uid(4), createdMs = 200)
        g.directory.apply(listOf(late))
        g.directory.apply(listOf(early)) // arrives after the later one: stored as a revocation only
        val k = g.db.rekeyDao().get(uid(2))!!
        assertArrayEquals(uid(4), k.newUserId)
        assertEquals(200L, k.createdMs)
        assertEquals(100L, k.revokedMs) // revoked since the earliest REKEY

        // equal created_ms: the lower record key wins (author is the network for both: the lower id)
        val lo = rekey(uid(3), uid(4), createdMs = 300, id = ByteArray(16) { 1 })
        val hi = rekey(uid(3), uid(2), createdMs = 300, id = ByteArray(16) { 2 })
        g.directory.apply(listOf(hi, lo))
        assertArrayEquals(uid(4), g.db.rekeyDao().get(uid(3))!!.newUserId)
        g.directory.apply(listOf(hi))
        assertArrayEquals(uid(4), g.db.rekeyDao().get(uid(3))!!.newUserId)
    }

    @Test fun theOldAccountInvitesNobodyAfterTheRekey() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna")))
        val before = invitedBy(2, 20, "zoe", createdMs = 400)
        val after = invitedBy(2, 21, "thief", createdMs = 600)
        g.directory.apply(listOf(member(3, "anna"), rekey(uid(2), uid(3), createdMs = 500), before, after))
        assertNotNull(g.db.contactDao().get(uid(20))) // invited while the old phone was still trusted
        assertNull(g.db.contactDao().get(uid(21)))    // the lost key invited after the REKEY
    }

    @Test fun theNewAccountIsNeverADuplicateLoser() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "anna")))
        // the admin's network invite for the new install was redeemed twice (an intercepted link)
        val s = ByteArray(32) { 6 }
        val shared = invite(node.network, networkId, s)
        val x = entry(7, "anna", shared, s)
        val y = entry(8, "anna", shared, s)
        val proof = { r: Record -> sha256(DirectoryEntry.parseFrom(RecordBody.parseFrom(r.body).payload).inviteProof.toByteArray()) }
        val loser: Byte = if (Membership.compareUnsigned(proof(x), proof(y)) < 0) 8 else 7
        val winner: Byte = if (loser == 7.toByte()) 8 else 7
        val z = invitedBy(loser, 20, "zoe", createdMs = 10)
        g.directory.apply(listOf(x, y, z))
        val c = g.db.contactDao()
        assertTrue(c.get(uid(loser))!!.revoked) // before the REKEY: an ordinary duplicate loser
        assertNull(c.get(uid(20)))

        // the person read the loser's account code to the admin: the REKEY re-enables it, and it can invite
        g.directory.apply(listOf(x, y, z, rekey(uid(2), uid(loser))))
        assertFalse(c.get(uid(loser))!!.revoked)
        assertFalse(c.get(uid(winner))!!.revoked)
        assertNotNull(c.get(uid(20)))
        assertFalse(c.get(uid(20))!!.revoked)
        assertFalse(uid(loser).joinToString("") { "%02x".format(it) } in g.settings.duplicateLosers())
        assertFalse(g.settings.accountRevoked())
    }

    @Test fun ourOwnOldAccountIsDisabled() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(3, "me_user"), rekey(g.me.userId, uid(3))))
        assertTrue(g.settings.accountRevoked())
    }

    private suspend fun memberLines(cid: Long) = g.db.messageDao().forConversation(cid).filter { it.kind == MessageKind.SYSTEM_MEMBER }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private suspend fun syncFromNode() { if (g.session == null) g.online(); g.directory.sync(g.session!!) }

    /** Group messages the old account sent before the re-key keep its name (the old contact is revoked, not forgotten). */
    @Test fun oldSendersKeepTheirName() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.directory.apply(listOf(member(2, "bob")))
        g.directory.apply(listOf(member(3, "bob"), rekey(uid(2), uid(3))))
        assertTrue(g.db.contactDao().get(uid(2))!!.revoked)
        val inChat = g.messages.observeContacts().first().associate { it.userId.hex() to it.displayName }
        assertEquals("BOB", inChat[uid(2).hex()])
        assertEquals("BOB", inChat[uid(3).hex()])
        val inList = ContactRepository(g.db.contactDao()).observeNames().first()
        assertEquals("BOB", inList[uid(2).hex()])
        // the new-chat and new-group pickers still list only live members
        assertTrue(ContactRepository(g.db.contactDao()).observeContacts().first().none { it.userId.contentEquals(uid(2)) })
    }

    /**
     * Step 1 of `otter-admin user rekey` (REVOCATION reason "rekey") is not a removal: no «Участник удалён» line, the chat
     * shows sending as paused until the REKEY moves it to the new account.
     */
    @Test fun rekeyRevocationIsNotARemoval() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val bob = member(2, "bob")
        val newBob = member(3, "bob")
        node.directoryAnswer = { d -> d + bob }
        syncFromNode()
        val cid = g.db.conversationDao().getOrCreateDirect(uid(2), "BOB", clock.now())
        node.revoke(uid(2), reason = "rekey")
        syncFromNode()
        assertTrue(g.db.contactDao().get(uid(2))!!.revoked) // nothing is sent to the lost key meanwhile
        assertEquals(0, memberLines(cid).size)
        assertTrue(uid(2).hex() in g.messages.observeRekeyingIds().first())

        clock.advance(3600_000)
        node.rekey(uid(2), uid(3))
        node.directoryAnswer = { d -> d + bob + newBob }
        syncFromNode()
        assertEquals(cid, g.db.conversationDao().byPeer(uid(3))!!.id)
        assertEquals(0, memberLines(cid).size)
        assertEquals(1, keyChangedLines(cid))
        clock.advance(GroupRevocations.REKEY_WAIT_MS + 1)
        syncFromNode()
        assertEquals(0, memberLines(cid).size) // the REKEY came: never a removal
    }

    /** Without a REKEY within REKEY_WAIT_MS the re-key revocation is an ordinary removal, line written once. */
    @Test fun rekeyRevocationWithoutRekeyBecomesARemoval() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val bob = member(2, "bob")
        node.directoryAnswer = { d -> d + bob }
        syncFromNode()
        val cid = g.db.conversationDao().getOrCreateDirect(uid(2), "BOB", clock.now())
        node.revoke(uid(2), reason = "rekey")
        syncFromNode()
        clock.advance(GroupRevocations.REKEY_WAIT_MS - 1000)
        syncFromNode()
        assertEquals(0, memberLines(cid).size)
        clock.advance(2000)
        syncFromNode()
        assertEquals(listOf("revoked"), memberLines(cid).map { it.systemData!!.decodeToString() })
        assertFalse(uid(2).hex() in g.messages.observeRekeyingIds().first())
        syncFromNode()
        assertEquals(1, memberLines(cid).size)
    }

    /** An ordinary REVOCATION still writes the line at once, and a late-seen expired re-key revocation does too. */
    @Test fun ordinaryAndExpiredRevocationsWriteTheLine() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val ann = member(4, "ann")
        val bob = member(2, "bob")
        node.directoryAnswer = { d -> d + ann + bob }
        syncFromNode()
        val annCid = g.db.conversationDao().getOrCreateDirect(uid(4), "ANN", clock.now())
        val bobCid = g.db.conversationDao().getOrCreateDirect(uid(2), "BOB", clock.now())
        node.revoke(uid(4))
        node.revoke(uid(2), reason = "rekey", createdMs = clock.now() - GroupRevocations.REKEY_WAIT_MS - 1) // phone was away
        syncFromNode()
        assertEquals(1, memberLines(annCid).size)
        assertEquals(1, memberLines(bobCid).size)
    }
}
