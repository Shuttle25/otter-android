package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.RegistrationError
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.signal.SignalKeyValue
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RegisterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RegistrationTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val graphs = mutableListOf<TestGraph>()
    private fun graph() = TestGraph(node, clock).also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun assertNothingPersisted(g: TestGraph) = runBlocking {
        assertNull(g.db.accountDao().get())
        assertNull(g.db.manifestDao().latest())
        assertTrue(withContext(Dispatchers.IO) { SignalKeyValue.Table.entries.all { g.db.signalKvDao().keys(it.id).isEmpty() } })
        assertNull(g.db.settingsDao().get("registration_id"))
    }

    @Test fun bootstrapRegistrationPersistsEverything() = runBlocking {
        val g = graph()
        g.register(node.bootstrapLink(), " Мама ", "Mama").getOrThrow()
        val acc = g.db.accountDao().get()!!
        assertEquals("mama", acc.username)
        assertEquals("Мама", acc.displayName)
        assertNotNull(g.accounts.account.value)
        assertEquals(1L, g.db.manifestDao().latest()!!.version)
        withContext(Dispatchers.IO) {
            assertTrue(g.db.signalKvDao().keys(SignalKeyValue.Table.SIGNED_PREKEY.id).isNotEmpty())
            assertEquals(50, g.db.signalKvDao().keys(SignalKeyValue.Table.PREKEY.id).size)
        }
        assertEquals(50, node.oneTimeLeft(g.me.userId))
        assertEquals(51, g.settings.nextOneTimeId())
        assertTrue(g.db.contactDao().get(ByteArray(16)) == null) // bootstrap invite has no inviter contact
    }

    @Test fun usernameTakenPersistsNothing() = runBlocking {
        graph().register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val g = graph()
        val r = g.register(node.bootstrapLink(), "Другая", "mama")
        assertEquals(RegistrationError.UsernameTaken, r.exceptionOrNull())
        assertNothingPersisted(g)
    }

    @Test fun errorMapping() = runBlocking {
        val link = node.bootstrapLink()
        graph().register(link, "Мама", "mama").getOrThrow()
        assertEquals(RegistrationError.InviteUsed, graph().register(link, "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.InviteExpired, graph().register(node.bootstrapLink(ttlMs = -1), "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.BadLink, graph().register("https://example.org", "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.BadUsername, graph().register(node.bootstrapLink(), "Боря", "b!").exceptionOrNull())
        assertEquals(RegistrationError.BadDisplayName, graph().register(node.bootstrapLink(), "   ", "borya").exceptionOrNull())

        // a link made on another node: the signed INVITE travels in the entry, so this node admits it too (D100)
        val other = FakeNode(clock::now)
        val g = graph()
        g.connector = object : NodeConnector {
            override suspend fun <T> withNode(candidates: List<app.ottermsg.core.network.Candidate>, block: suspend (NodeApi) -> T): T = block(node.connect())
        }
        g.register(other.bootstrapLink(), "Боря", "borya").getOrThrow()

        val offline = graph()
        offline.connector = object : NodeConnector {
            override suspend fun <T> withNode(candidates: List<app.ottermsg.core.network.Candidate>, block: suspend (NodeApi) -> T): T = throw IOException("down")
        }
        assertEquals(RegistrationError.Network, offline.register(node.bootstrapLink(), "Боря", "borya").exceptionOrNull())
        assertNothingPersisted(offline)
    }

    @Test fun registrationCarriesChainNotSecret() = runBlocking {
        val g = graph()
        val sent = mutableListOf<RegisterRequest>()
        node.onRegister = { sent += it }
        val link = node.bootstrapLink()
        val bundle = InviteLink.decode(link)
        g.register(link, "Я", "me_user").getOrThrow()
        val req = sent.single()
        assertTrue(req.inviteSecret.isEmpty)
        assertFalse(req.toByteArray().toList().windowed(32).any { it == bundle.secret.toByteArray().toList() }) // the secret is nowhere in the request
        val e = DirectoryEntry.parseFrom(RecordBody.parseFrom(req.directoryEntry.body).payload)
        assertEquals(bundle.invite.toByteString(), e.inviteRecord)
        val inv = Invite.parseFrom(RecordBody.parseFrom(Record.parseFrom(e.inviteRecord).body).payload)
        assertTrue(Ed25519.verify(inv.redeemPub.toByteArray(), InviteKeys.redeemMessage(inv.inviteId.toByteArray(),
            g.me.userId, g.me.accountKey.publicKey), e.inviteProof.toByteArray()))
        assertEquals(e.inviteRecord.toByteArray().toList(), g.settings.ownInvite()!!.first.toList())

        // a profile update keeps the chain
        g.online()
        g.profile.setDisplayName("Я2").getOrThrow()
        eventually(what = "published") { node.directoryUpdates(g.me.userId) == 1 }
        val v2 = node.directoryRecords().map { DirectoryEntry.parseFrom(RecordBody.parseFrom(it.body).payload) }
            .single { it.userId.toByteArray().contentEquals(g.me.userId) }
        assertEquals(2L, v2.version)
        assertEquals(e.inviteRecord, v2.inviteRecord)
        assertEquals(e.inviteProof, v2.inviteProof)
    }

    @Test fun oldLinkIsRefusedLocally() = runBlocking {
        val g = graph()
        val old = InviteLink.decode(node.bootstrapLink()).toBuilder().clearInvite().build()
        val r = g.register(InviteLink.encode(old), "Я", "me_user")
        assertEquals(RegistrationError.InviteOutdated, r.exceptionOrNull())
        assertEquals(0, node.registrations)
        assertNothingPersisted(g)
    }

    @Test fun linkWhoseInviteDoesNotMatchItsSecretIsRefused() = runBlocking {
        // A tampered or swapped link: its INVITE belongs to another secret, so no proof could verify. Refused locally.
        val a = InviteLink.decode(node.bootstrapLink())
        val b = InviteLink.decode(node.bootstrapLink())
        val g = graph()
        assertEquals(RegistrationError.BadLink, g.register(InviteLink.encode(a.toBuilder().setInvite(b.invite).build()), "Я", "me_user").exceptionOrNull())
        assertEquals(0, node.registrations)
        assertNothingPersisted(g)
    }

    @Test fun nodeRefusesAWrongProof() = runBlocking {
        // A phone (or a hostile relay) that sends the INVITE without a valid proof is refused by the node, like Go admitChain.
        val g = graph()
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val good = node.directoryRecords().single()
        val e = DirectoryEntry.parseFrom(RecordBody.parseFrom(good.body).payload)
        val other = Ed25519.keyPairFromSeed(ByteArray(32) { 3 })
        val forged = e.toBuilder().setUserId(bs(app.ottermsg.core.crypto.Ids.id16(other.publicKey))).setAccountPub(bs(other.publicKey))
            .setUsername("thief").build()
        val rec = app.ottermsg.core.crypto.RecordCodec.sign(app.ottermsg.core.crypto.RecordCodec.body(app.ottermsg.proto.v1.RecordType.DIRECTORY_ENTRY,
            app.ottermsg.core.crypto.Ids.id16(other.publicKey), forged.toByteArray(), createdMs = clock.now()), other)
        val err = runCatching { node.connect().register(RegisterRequest.newBuilder().setDirectoryEntry(rec).setPrekeys(rec).setInviteUsed(rec).build()) }
            .exceptionOrNull() as app.ottermsg.core.network.RpcException
        assertEquals(app.ottermsg.proto.v1.Status.STATUS_INVALID, err.status)
        assertTrue(err.message!!.contains("invite proof"))
    }
}
