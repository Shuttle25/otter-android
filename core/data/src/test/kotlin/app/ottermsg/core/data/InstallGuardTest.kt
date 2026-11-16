package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.proto.v1.Install
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D52 + C14: a phone stops only for an account-signed newer INSTALL; nothing a node says on its own can stop it. */
@RunWith(RobolectricTestRunner::class)
class InstallGuardTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val mallory = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })

    @After fun tearDown() = a.close()

    private fun install(rid: Int, installedMs: Long, key: Ed25519.KeyPair = a.me.accountKey, author: ByteArray = a.me.userId): Record =
        RecordCodec.sign(RecordCodec.body(RecordType.INSTALL, author, Install.newBuilder().setRegistrationId(rid)
            .setInstalledMs(installedMs).build().toByteArray(), createdMs = clock.now()), key, node.ctx)

    @Test fun registrationPublishesSignedInstall() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val installed = a.settings.installedMs()
        a.online()
        assertEquals(installed, node.installOf(a.me.userId)!!.installedMs)
        assertEquals(a.me.registrationId, node.installOf(a.me.userId)!!.registrationId)
        assertTrue(a.settings.installPublished())
        a.prekeys.refill(a.session!!, node.nodeId)
        assertEquals(installed, node.loginInfo(a.me.userId).second) // a refill is not a new install
        assertFalse(a.settings.superseded())
    }

    @Test fun hostileNodeCannotSupersedeThisPhone() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online(); a.offline()
        val far = a.settings.installedMs() + 1_000_000
        node.lieInstalledMs = Long.MAX_VALUE // unsigned claim
        a.online(); a.offline()
        assertFalse(a.settings.superseded())
        val lies = listOf(
            install(77, far, key = mallory),                                        // another key
            install(77, far, key = mallory, author = Ids.id16(mallory.publicKey)),  // another author
            Record.newBuilder().setBody(bs(ByteArray(10) { 1 })).setSignature(bs(ByteArray(64))).build(), // garbage
            install(a.me.registrationId, a.settings.installedMs() - 1),             // replay of an older own install
        )
        for (lie in lies) {
            node.fakeInstall = lie
            a.online(); a.offline()
            assertFalse(a.settings.superseded())
        }
    }

    @Test fun signedNewerInstallSupersedesThisPhone() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online(); a.offline()
        val other = node.connect().apply { login(a.me.userId, a.me.accountKey) }
        other.publishInstall(install(77, a.settings.installedMs() + 1000))
        a.online()
        assertTrue(a.settings.superseded())
        assertEquals(77, node.loginInfo(a.me.userId).first) // the node keeps the newer install
        a.online() // a later start stops before logging in
        assertTrue(a.settings.superseded())
    }

    @Test fun oldNodeRefusingInstallPublishDoesNotBreakTheSession() = runBlocking<Unit> {
        node.refuseInstall = true // 0.8.x node
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        a.session!!.ping() // still logged in and usable
        assertFalse(a.settings.installPublished())
        assertFalse(a.settings.superseded())
        a.offline()
        node.refuseInstall = false // the node is upgraded: the next login publishes
        a.online()
        assertTrue(a.settings.installPublished())
        assertEquals(a.settings.installedMs(), node.installOf(a.me.userId)!!.installedMs)
    }
}
