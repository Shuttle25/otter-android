package app.ottermsg.core.data

import app.ottermsg.core.crypto.BackupKeys
import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.data.backup.RestoreManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
