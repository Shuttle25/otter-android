package app.ottermsg.core.data

import app.ottermsg.core.crypto.BackupKeys
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.BackupState
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.data.backup.SnapshotCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun family() {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow(); a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow(); b.online()
        eventually(what = "a knows b") { a.db.contactDao().get(b.me.userId) != null }
    }

    /** Downloads the current backup like a restoring phone and decrypts it with keys re-derived from [password]. */
    private suspend fun download(username: String, password: String): app.ottermsg.proto.v1.BackupSnapshot {
        val s = node.connect()
        val auth = s.backupAuth(username)
        val keys = BackupKeys.derive(password, auth.salt.toByteArray(), mKib = 1024, t = 1, p = 1)
        val res = s.backupFetch(username, keys.authSecret)
        val meta = app.ottermsg.proto.v1.BackupMeta.parseFrom(app.ottermsg.proto.v1.RecordBody.parseFrom(res.backupMeta.body).payload)
        val blob = app.ottermsg.proto.v1.BlobMeta.parseFrom(app.ottermsg.proto.v1.RecordBody.parseFrom(res.blobMeta.body).payload)
        val f = File.createTempFile("restore", ".otb")
        f.outputStream().use { o -> for (i in 0 until blob.chunkSha256Count) o.write(s.blobGet(meta.blobKey.toByteArray(), i)) }
        return BlobCipher.decrypt(f, keys.backupKey, meta.blobKey.toByteArray()).use { SnapshotCodec.gunzip(it) }.also { f.delete() }
    }

    @Test fun enableRunDailyAndChangePassword() = runBlocking<Unit> {
        family()
        val cid = a.messages.conversationWith(b.me.userId)
        a.messages.sendText(cid, "до бэкапа")
        assertEquals(BackupState.Off, a.backups.state.first())

        assertEquals(BackupManager.ERR_WEAK, a.backups.setPassword("password12"))
        assertNull(a.settings.backupSecret())

        val pw = "корова лодка зима семь"
        assertEquals(BackupManager.OK, a.backups.setPassword(pw))
        assertEquals(1L, node.backupOf(a.me.userId)!!.version)
        val snap = download("mama", pw)
        assertArrayEquals(a.seed, snap.seed.toByteArray())
        assertTrue(snap.messagesList.any { it.body == "до бэкапа" })
        val firstBlob = node.backupOf(a.me.userId)!!.blobKey.toByteArray()
        val st = a.backups.state.first() as BackupState.On
        assertTrue(st.lastMs > 0); assertEquals(0, st.error); assertFalse(st.running)

        a.backups.runIfDue(a.session!!)
        assertEquals(1L, node.backupOf(a.me.userId)!!.version) // less than 24 h
        clock.advance(25 * 3_600_000L)
        a.backups.runIfDue(a.session!!)
        assertEquals(2L, node.backupOf(a.me.userId)!!.version)
        assertTrue(node.blobGone(firstBlob))

        val salt1 = node.backupOf(a.me.userId)!!.salt
        val pw2 = "ёжик туман лошадь река"
        assertEquals(BackupManager.OK, a.backups.setPassword(pw2))
        assertEquals(3L, node.backupOf(a.me.userId)!!.version)
        assertFalse(salt1 == node.backupOf(a.me.userId)!!.salt)
        download("mama", pw2)

        a.offline()
        assertEquals(BackupManager.ERR_OFFLINE, a.backups.runNow())
        assertEquals(BackupManager.ERR_OFFLINE, (a.backups.state.first() as BackupState.On).error)
    }

    @Test fun sessionRunsDueBackup() = runBlocking<Unit> {
        family()
        assertEquals(BackupManager.OK, a.backups.setPassword("корова лодка зима семь"))
        clock.advance(25 * 3_600_000L)
        a.online() // a new session backs up when the last one is older than a day
        eventually(what = "daily backup") { node.backupOf(a.me.userId)?.version == 2L }
    }

    @Test fun accountWithoutBackupLooksLikeOneWithBackup() = runBlocking<Unit> {
        family()
        eventually(what = "decoys published") { node.backupOf(a.me.userId) != null && node.backupOf(b.me.userId) != null }
        val decoy = node.backupOf(a.me.userId)!!
        assertEquals(0L, decoy.version)
        assertEquals(16, decoy.salt.size())
        assertEquals(0L, decoy.size)
        assertArrayEquals(a.me.userId + ByteArray(16), decoy.blobKey.toByteArray())
        val auth = node.connect().backupAuth("mama")
        assertEquals(decoy.salt, auth.salt)
        assertEquals(listOf(65536, 3, 1), listOf(auth.kdfMKib, auth.kdfT, auth.kdfP))
        val phone = TestGraph(node, clock)
        try {
            assertEquals(RestoreError.WrongPassword, phone.restore.withPassword(node.bootstrapLink(), "mama", "любой пароль здесь").exceptionOrNull())
        } finally { phone.close() }

        a.offline(); a.online() // once per install
        assertEquals(decoy, node.backupOf(a.me.userId))
        assertEquals(BackupManager.OK, a.backups.setPassword("корова лодка зима семь"))
        assertEquals(1L, node.backupOf(a.me.userId)!!.version)
    }

    @Test fun nodeRefusingDecoyIsRetriedNextSession() = runBlocking<Unit> {
        node.refuseDecoy = true // a 0.8.x node
        family() // the session goes on: b joins and a learns of b
        assertNull(node.backupOf(a.me.userId))
        assertFalse(a.settings.backupDecoyDone())

        node.refuseDecoy = false // the node is upgraded
        a.offline(); a.online()
        eventually(what = "decoy on the next session") { node.backupOf(a.me.userId)?.version == 0L }
        assertTrue(a.settings.backupDecoyDone())
    }
}
