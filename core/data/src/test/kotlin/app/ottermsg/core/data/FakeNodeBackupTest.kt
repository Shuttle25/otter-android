package app.ottermsg.core.data

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.BackupMeta
import app.ottermsg.proto.v1.BlobMeta
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.SignedPreKey
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/** FakeNode follows the Go rules for backups and installs (D50–D52), so higher-level tests can rely on it. */
@RunWith(RobolectricTestRunner::class)
class FakeNodeBackupTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)

    @After fun tearDown() = a.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun signed(type: RecordType, payload: ByteArray): Record =
        RecordCodec.sign(RecordCodec.body(type, a.me.userId, payload, createdMs = clock.now()), a.me.accountKey)

    private suspend fun putBlob(s: FakeNode.Session, data: ByteArray): ByteArray {
        val meta = signed(RecordType.BLOB_META, BlobMeta.newBuilder().setSize(data.size.toLong()).setChunkSize(262144)
            .addChunkSha256(bs(sha(data))).setPersistent(true).build().toByteArray())
        s.blobCreate(meta)
        val b = app.ottermsg.proto.v1.RecordBody.parseFrom(meta.body)
        val key = b.author.toByteArray() + b.id.toByteArray()
        s.blobPut(key, 0, data)
        return key
    }

    private fun backupMeta(version: Long, blob: ByteArray, secret: ByteArray, salt: ByteArray) =
        signed(RecordType.BACKUP_META, BackupMeta.newBuilder().setVersion(version).setSalt(bs(salt)).setKdfMKib(65536).setKdfT(3)
            .setKdfP(1).setAuthVerifier(bs(sha(secret))).setBlobKey(bs(blob)).setSize(10).build().toByteArray())

    private suspend inline fun expect(status: Status, block: () -> Unit) {
        try { block(); fail("expected $status") } catch (e: RpcException) { assertEquals(status, e.status) }
    }

    @Test fun backupLatestWinsFakeSaltGrantAndLimit() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val s = node.connect().apply { login(a.me.userId, a.me.accountKey) }
        val secret = ByteArray(32) { 1 }
        val salt = ByteArray(16) { 2 }
        val b1 = putBlob(s, ByteArray(10) { 3 })
        s.backupPublish(backupMeta(1, b1, secret, salt))
        val b2 = putBlob(s, ByteArray(10) { 4 })
        s.backupPublish(backupMeta(2, b2, secret, salt))
        expect(Status.STATUS_INVALID) { s.backupPublish(backupMeta(1, b1, secret, salt)) } // its blob is gone (as on the Go node)
        s.backupPublish(backupMeta(1, b2, secret, salt)) // older version: ignored
        assertEquals(2L, node.backupOf(a.me.userId)!!.version)
        assertEquals(true, node.blobGone(b1))

        val fresh = node.connect()
        assertArrayEquals(salt, fresh.backupAuth("mama").salt.toByteArray())
        val fake = fresh.backupAuth("nobody").salt.toByteArray()
        assertArrayEquals(fake, fresh.backupAuth("nobody").salt.toByteArray())
        assertFalse(fake.contentEquals(fresh.backupAuth("somebody").salt.toByteArray()))
        expect(Status.STATUS_UNAUTHORIZED) { fresh.blobGet(b2, 0) }
        val res = fresh.backupFetch("mama", secret)
        assertEquals(RecordType.BACKUP_META, app.ottermsg.proto.v1.RecordBody.parseFrom(res.backupMeta.body).type)
        assertArrayEquals(ByteArray(10) { 4 }, fresh.blobGet(b2, 0))
        expect(Status.STATUS_UNAUTHORIZED) { fresh.blobGet(b1, 0) }

        val bad = node.connect()
        repeat(5) { expect(Status.STATUS_UNAUTHORIZED) { bad.backupFetch("mama", ByteArray(32)) } }
        expect(Status.STATUS_RETRY_LATER) { bad.backupFetch("mama", secret) }
        clock.advance(3_700_000)
        bad.backupFetch("mama", secret)
    }

    @Test fun newestInstallWins() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val s = node.connect().apply { login(a.me.userId, a.me.accountKey) }
        val (rid0, inst0) = node.loginInfo(a.me.userId)
        assertEquals(0L, inst0)
        val key = SignedPreKey.newBuilder().setId(9).setPublicKey(bs(ByteArray(33))).setSignature(bs(ByteArray(64))).build()
        s.uploadPrekeys(signed(RecordType.PREKEYS, PreKeys.newBuilder().setDeviceId(1).setRegistrationId(rid0 + 1)
            .setInstalledMs(5000).setSignedPrekey(key).setKyberPrekey(key).build().toByteArray()))
        assertEquals(rid0 + 1 to 5000L, node.loginInfo(a.me.userId))
        assertEquals(0, node.oneTimeLeft(a.me.userId))
        s.uploadPrekeys(signed(RecordType.PREKEYS, PreKeys.newBuilder().setDeviceId(1).setRegistrationId(rid0)
            .setInstalledMs(0).build().toByteArray())) // the replaced phone refills late
        assertEquals(rid0 + 1 to 5000L, node.loginInfo(a.me.userId))
        val again = node.connect()
        val r = again.login(a.me.userId, a.me.accountKey)
        assertEquals(rid0 + 1, r.registrationId); assertEquals(5000L, r.installedMs)
    }
}
