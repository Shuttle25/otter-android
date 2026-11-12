package app.ottermsg.core.data

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.SignedPreKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D52: a phone notices that a restore on another phone replaced it, and stops. */
@RunWith(RobolectricTestRunner::class)
class InstallGuardTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)

    @After fun tearDown() = a.close()

    @Test fun registrationStoresInstallAndRefillKeepsIt() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val installed = a.settings.installedMs()
        assertTrue(installed > 0)
        assertEquals(installed, node.loginInfo(a.me.userId).second)
        a.online()
        a.prekeys.refill(a.session!!, node.nodeId)
        assertEquals(installed, node.loginInfo(a.me.userId).second) // a refill is not a new install
        assertFalse(a.settings.superseded())
    }

    @Test fun newerInstallElsewhereSupersedesThisPhone() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        a.offline()
        // the same account restored on another phone: a newer install uploads its prekeys
        val other = node.connect().apply { login(a.me.userId, a.me.accountKey) }
        val key = SignedPreKey.newBuilder().setId(1).setPublicKey(bs(ByteArray(33))).setSignature(bs(ByteArray(64))).build()
        other.uploadPrekeys(RecordCodec.sign(RecordCodec.body(RecordType.PREKEYS, a.me.userId, PreKeys.newBuilder().setDeviceId(1)
            .setRegistrationId(77).setInstalledMs(a.settings.installedMs() + 1000).setSignedPrekey(key).setKyberPrekey(key)
            .build().toByteArray(), createdMs = clock.now()), a.me.accountKey))
        a.online()
        assertTrue(a.settings.superseded())
        // the node keeps the newer install
        assertEquals(77, node.loginInfo(a.me.userId).first)
        a.online() // a later start stops before logging in
        assertTrue(a.settings.superseded())
    }
}
