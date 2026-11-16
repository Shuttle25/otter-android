package app.ottermsg.core.data

import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.invite.NoInviteRightsException
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/** D100: a member's invite carries a redeem key and travels as a signed record in the link. */
@RunWith(RobolectricTestRunner::class)
class InviteChainTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)
    @After fun tearDown() { a.close(); b.close() }

    @Test fun memberInviteCarriesTheSignedRecordAndRedeemKey() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        val bundle = InviteLink.decode(a.invites.create().link)
        val body = RecordBody.parseFrom(bundle.invite.body)
        assertEquals(RecordType.INVITE, body.type)
        assertArrayEquals(a.me.userId, body.author.toByteArray())
        assertTrue(RecordCodec.verify(bundle.invite, a.me.accountKey.publicKey, a.accounts.sigContext()))
        val inv = Invite.parseFrom(body.payload)
        assertEquals(7 * 24 * 3_600_000L, inv.expiresMs - body.createdMs) // never longer than 7 days (D107 allows 30)
        val secret = bundle.secret.toByteArray()
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(secret), inv.inviteId.toByteArray())
        assertArrayEquals(InviteKeys.redeemKey(secret).publicKey, inv.redeemPub.toByteArray())

        // B joins through A's invite: B's entry carries A's record, and A's own entry the network's
        b.register(InviteLink.encode(bundle), "Боря", "borya").getOrThrow()
        val bEntry = node.directoryRecords().map { DirectoryEntry.parseFrom(RecordBody.parseFrom(it.body).payload) }
            .single { it.userId.toByteArray().contentEquals(b.me.userId) }
        assertEquals(bundle.invite.toByteString(), bEntry.inviteRecord)
    }

    @Test fun withdrawnRightsAreReported() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        node.withdrawInviteRights(a.me.userId)
        val e = runCatching { a.invites.create() }.exceptionOrNull()
        assertTrue("got $e", e is NoInviteRightsException)
    }

    @Test fun restoredPhoneLearnsItsChainFromItsOwnEntry() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        val chain = a.settings.ownInvite()!!
        val link = a.invites.create().link // only its manifest is used
        a.offline()
        b.restore.withWords(link, Bip39.toMnemonic(a.seed)).getOrThrow()
        assertEquals(null, b.settings.ownInvite())
        b.profile.setDisplayName("Мама 2").getOrThrow() // an edit made before the first sync still carries the chain
        b.online()
        eventually(what = "own chain from the directory") { b.settings.ownInvite() != null }
        assertArrayEquals(chain.first, b.settings.ownInvite()!!.first)
        assertArrayEquals(chain.second, b.settings.ownInvite()!!.second)
        eventually(what = "published") { node.directoryUpdates(a.me.userId) == 1 }
        val v2 = node.directoryRecords().map { DirectoryEntry.parseFrom(RecordBody.parseFrom(it.body).payload) }
            .single { it.userId.toByteArray().contentEquals(a.me.userId) }
        assertEquals("Мама 2", v2.displayName)
        assertArrayEquals(chain.first, v2.inviteRecord.toByteArray())
        assertArrayEquals(chain.second, v2.inviteProof.toByteArray())
    }
}
