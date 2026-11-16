package app.ottermsg.core.data

import app.ottermsg.core.crypto.BackupKeys
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.data.backup.RestoreManager
import app.ottermsg.proto.v1.BackupMeta
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.RecordBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M9.1: a node lies in its restore answers; the phone refuses before it installs anything. */
@RunWith(RobolectricTestRunner::class)
class HostileRestoreTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val anna = TestGraph(node, clock)
    private val graphs = mutableListOf(anna)
    private val pw = "корова лодка зима семь"
    private val mallory = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
    private var derivations = 0

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun fresh() = TestGraph(node, clock).also { graphs += it }

    /** A restore whose (cheap) key derivation counts its calls: a refused answer must not use the password. */
    private fun TestGraph.counting(): RestoreManager = restore.apply {
        derive = { pass, salt, _ -> derivations++; BackupKeys.derive(pass, salt, mKib = 1024, t = 1, p = 1) }
    }

    private suspend fun annaBacksUp() {
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow(); anna.online()
        assertEquals(BackupManager.OK, anna.backups.setPassword(pw))
        anna.offline()
    }

    @Test fun nodeChosenKdfIsRefusedBeforeThePasswordIsUsed() = runBlocking<Unit> {
        annaBacksUp()
        val link = node.bootstrapLink()
        val bad = listOf(Triple(8, 1, 1), Triple(4_194_304, 3, 1), Triple(65536, 1, 1), Triple(65536, 3, 4))
        for ((m, t, p) in bad) {
            node.authAnswer = { it.toBuilder().setKdfMKib(m).setKdfT(t).setKdfP(p).build() }
            val phone = fresh()
            assertEquals(RestoreError.Corrupt, phone.counting().withPassword(link, "anna", pw).exceptionOrNull())
            assertNull(phone.db.accountDao().get())
        }
        node.authAnswer = { it.toBuilder().setSalt(bs(ByteArray(8))).build() }
        assertEquals(RestoreError.Corrupt, fresh().counting().withPassword(link, "anna", pw).exceptionOrNull())
        assertEquals(0, derivations)
        assertEquals(0, node.backupFetches) // no auth_secret left the phone

        node.authAnswer = null
        fresh().counting().withPassword(link, "anna", pw).getOrThrow() // the honest answer still restores
    }

    private fun resignedMeta(rec: app.ottermsg.proto.v1.Record, key: Ed25519.KeyPair, edit: (BackupMeta.Builder) -> Unit): app.ottermsg.proto.v1.Record {
        val body = RecordBody.parseFrom(rec.body)
        val m = BackupMeta.parseFrom(body.payload).toBuilder().also(edit).build()
        return RecordCodec.sign(body.toBuilder().setPayload(m.toByteString()).build(), key, node.ctx)
    }

    @Test fun swappedOrForgedMetaIsCorruptAndNothingIsInstalled() = runBlocking<Unit> {
        annaBacksUp()
        val link = node.bootstrapLink()
        val otherSalt = bs(ByteArray(16) { 1 })
        val cases = listOf<(app.ottermsg.proto.v1.BackupFetchResponse) -> app.ottermsg.proto.v1.BackupFetchResponse>(
            // a node swaps the salt and re-signs with its own key
            { r -> r.toBuilder().setBackupMeta(resignedMeta(r.backupMeta, mallory) { it.setSalt(otherSalt) }).build() },
            // a genuinely signed but different meta (an older password's salt)
            { r -> r.toBuilder().setBackupMeta(resignedMeta(r.backupMeta, anna.me.accountKey) { it.setSalt(otherSalt) }).build() },
            // the blob meta re-signed by another key (same chunk hashes)
            { r -> r.toBuilder().setBlobMeta(RecordCodec.sign(RecordBody.parseFrom(r.blobMeta.body), mallory, node.ctx)).build() },
            // a blob meta of another blob of the same author
            { r -> r.toBuilder().setBlobMeta(RecordCodec.sign(RecordBody.parseFrom(r.blobMeta.body).toBuilder()
                .setId(bs(ByteArray(16) { 3 })).build(), anna.me.accountKey, node.ctx)).build() },
        )
        for (lie in cases) {
            node.fetchAnswer = lie
            val phone = fresh()
            assertEquals(RestoreError.Corrupt, phone.counting().withPassword(link, "anna", pw).exceptionOrNull())
            assertNull(phone.db.accountDao().get())
            assertNull(phone.settings.backupSecret())
        }
        node.fetchAnswer = null
        fresh().counting().withPassword(link, "anna", pw).getOrThrow()
    }

    @Test fun restoredPhonesNextBackupWinsOverANewerOne() = runBlocking<Unit> {
        annaBacksUp() // version 1 on the node
        val phone = fresh()
        phone.counting().withPassword(node.bootstrapLink(), "anna", pw).getOrThrow()
        // other nodes had a newer backup than the one this node served
        anna.online(); anna.settings.setBackupVersion(4); assertEquals(BackupManager.OK, anna.backups.runNow()); anna.offline()
        assertEquals(5L, node.backupOf(anna.me.userId)!!.version)
        phone.online()
        eventually(what = "the restored phone's backup wins") { node.backupOf(anna.me.userId)!!.version > 5L }
        assertTrue(phone.settings.backupVersion() > 5L)
    }

    @Test fun wordsRestoreIgnoresForgedDirectoryEntries() = runBlocking<Unit> {
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow()
        val genuine = node.directoryRecords().first { RecordBody.parseFrom(it.body).author.toByteArray().contentEquals(anna.me.userId) }
        val body = RecordBody.parseFrom(genuine.body)
        val e = DirectoryEntry.parseFrom(body.payload).toBuilder().setUsername("mallory").setDisplayName("Мэллори").setVersion(99).build()
        val forged = RecordCodec.sign(body.toBuilder().setPayload(e.toByteString()).build(), mallory, node.ctx)
        val words = Bip39.toMnemonic(anna.seed)

        node.directoryAnswer = { listOf(forged) + it } // the forged entry first
        val phone = fresh()
        phone.restore.withWords(node.bootstrapLink(), words).getOrThrow()
        assertEquals("anna", phone.me.username)
        assertEquals("Анна", phone.me.displayName)

        node.directoryAnswer = { listOf(forged) }
        val other = fresh()
        assertEquals(RestoreError.UnknownAccount, other.restore.withWords(node.bootstrapLink(), words).exceptionOrNull())
        assertNull(other.db.accountDao().get())
    }
}
