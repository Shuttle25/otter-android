package app.ottermsg.core.data

import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.data.backup.RestoreManager
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcTimeoutException
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

/** M6 acceptance on FakeNode: restore with username + password (or words), contacts keep writing without doing anything. */
@RunWith(RobolectricTestRunner::class)
class RestoreFlowTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val anna = TestGraph(node, clock)
    private val boris = TestGraph(node, clock)
    private val graphs = mutableListOf(anna, boris)
    private val pw = "корова лодка зима семь"

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun newGraph() = TestGraph(node, clock).also { graphs += it }

    private suspend fun TestGraph.texts(peer: ByteArray) =
        messages.observeMessages(messages.conversationWith(peer)).first().filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun TestGraph.direct(peer: ByteArray) = messages.observeMessages(messages.conversationWith(peer)).first()
    private suspend fun TestGraph.groupTexts(gid: ByteArray) =
        db.conversationDao().byGroup(gid)?.let { db.messageDao().observe(it.id).first() }.orEmpty()
            .filter { it.kind == MessageKind.TEXT }.map { it.body }

    /** anna and boris chat 1:1 and in a group; anna backs up. Returns the group id. */
    private suspend fun history(): ByteArray {
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow(); anna.online()
        boris.register(anna.invites.create().link, "Борис", "boris").getOrThrow(); boris.online()
        eventually(what = "anna knows boris") { anna.db.contactDao().get(boris.me.userId) != null }
        anna.messages.sendText(anna.messages.conversationWith(boris.me.userId), "привет")
        eventually(what = "boris reads привет") { "привет" in boris.texts(anna.me.userId) }
        boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "как дела")
        eventually(what = "anna reads как дела") { "как дела" in anna.texts(boris.me.userId) }
        val gcid = anna.groups.create("Семья", true, listOf(boris.me.userId))
        val gid = checkNotNull(anna.db.conversationDao().get(gcid)!!.groupId)
        anna.messages.sendText(gcid, "всем")
        eventually(what = "boris reads всем") { "всем" in boris.groupTexts(gid) }
        assertEquals(BackupManager.OK, anna.backups.setPassword(pw))
        eventually(what = "anna's mailbox drained") { node.mailbox(anna.me.userId).isEmpty() }
        anna.offline() // the old phone is gone (until the end of the test)
        return gid
    }

    @Test fun restoreWithPasswordKeepsChatting() = runBlocking<Unit> {
        val gid = history()
        val restored = newGraph()
        val link = boris.invites.create().link // any family invite: only its manifest is used

        assertEquals(RestoreError.WrongPassword, restored.restore.withPassword(link, "anna", "неверный пароль тут").exceptionOrNull())
        assertEquals(RestoreError.WrongPassword, restored.restore.withPassword(link, "nobody", pw).exceptionOrNull())
        restored.restore.withPassword(link, "Anna ", pw).getOrThrow()

        assertEquals(anna.me.userId.toList(), restored.me.userId.toList())
        assertEquals("Анна", restored.me.displayName)
        assertEquals(listOf("привет", "как дела"), restored.texts(boris.me.userId))
        assertNotNull(restored.db.groupDao().get(gid))
        assertEquals(listOf("всем"), restored.groupTexts(gid))
        assertTrue(restored.settings.restorePending())
        assertTrue(restored.settings.backupSecret() != null)

        restored.online()
        eventually(what = "boris sees the reset") { boris.direct(anna.me.userId).any { it.kind == MessageKind.SYSTEM_RESET } }
        assertFalse(restored.settings.restorePending())
        assertEquals(restored.settings.installedMs(), node.installOf(anna.me.userId)!!.installedMs) // C14: signed INSTALL published
        assertTrue(restored.settings.installPublished())
        boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "ты вернулась!")
        eventually(what = "restored anna reads it") { "ты вернулась!" in restored.texts(boris.me.userId) }
        restored.messages.sendText(restored.messages.conversationWith(boris.me.userId), "да")
        eventually(what = "boris reads да") { "да" in boris.texts(anna.me.userId) }
        boris.messages.sendText(boris.db.conversationDao().byGroup(gid)!!.id, "в группе от бориса")
        eventually(what = "restored anna reads the group") { "в группе от бориса" in restored.groupTexts(gid) }
        restored.messages.sendText(restored.db.conversationDao().byGroup(gid)!!.id, "в группе от анны")
        eventually(what = "boris reads the group") { "в группе от анны" in boris.groupTexts(gid) }
        eventually(what = "restored phone backs up with the same password") { node.backupOf(anna.me.userId)!!.version > 1L }

        anna.online() // the old phone comes back
        eventually(what = "old phone superseded") { anna.settings.superseded() }
    }

    @Test fun oldPhoneTakesTheAccountBack() = runBlocking<Unit> {
        history()
        val restored = newGraph()
        restored.restore.withWords(boris.invites.create().link, Bip39.toMnemonic(anna.seed)).getOrThrow()
        restored.online()
        eventually(what = "boris sees the restored phone's reset") { boris.direct(anna.me.userId).any { it.kind == MessageKind.SYSTEM_RESET } }
        restored.offline()
        anna.online()
        assertTrue(anna.settings.superseded())
        anna.offline()

        anna.guard.reclaim() // «Этот телефон снова главный»
        assertFalse(anna.settings.superseded())
        anna.online()
        assertFalse(anna.settings.superseded())
        assertEquals(anna.settings.installedMs(), node.installOf(anna.me.userId)!!.installedMs)
        eventually(what = "boris sees anna's second reset") { boris.direct(anna.me.userId).count { it.kind == MessageKind.SYSTEM_RESET } >= 2 }
        boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "снова тут")
        eventually(what = "anna reads it") { "снова тут" in anna.texts(boris.me.userId) }

        restored.online()
        assertTrue(restored.settings.superseded())
    }

    @Test fun restoreWithWordsThenHistory() = runBlocking<Unit> {
        history()
        val words = Bip39.toMnemonic(anna.seed)
        val restored = newGraph()
        val link = boris.invites.create().link
        assertEquals(RestoreError.BadWords, restored.restore.withWords(link, words.reversed()).exceptionOrNull())
        restored.restore.withWords(link, words.map { it.uppercase() }).getOrThrow()
        assertEquals(anna.me.userId.toList(), restored.me.userId.toList())
        assertEquals("anna", restored.me.username)
        assertTrue(restored.db.conversationDao().all().isEmpty())

        restored.online() // contacts from the directory, then a reset to each of them
        eventually(what = "boris sees the reset") { boris.direct(anna.me.userId).any { it.kind == MessageKind.SYSTEM_RESET } }
        boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "это ты?")
        eventually(what = "restored anna reads it") { "это ты?" in restored.texts(boris.me.userId) }

        restored.restore.addHistory(pw).getOrThrow()
        assertEquals(listOf("привет", "как дела", "это ты?"), restored.texts(boris.me.userId))
        assertTrue(restored.settings.backupSecret() != null)
    }

    private val pw2 = "второй пароль для бориса"
    private fun tag(user: ByteArray) = user.copyOf(4).joinToString("") { "%02x".format(it) }

    /** anna and boris both end up named "anna" (the clash a partition would produce, D103); both back up. */
    private suspend fun clash() {
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow(); anna.online()
        boris.register(node.bootstrapLink(), "Анна 2", "anna_x").getOrThrow(); boris.online()
        assertEquals(BackupManager.OK, anna.backups.setPassword(pw))
        assertEquals(BackupManager.OK, boris.backups.setPassword(pw2))
        anna.offline(); boris.offline()
        node.renameForTest(boris.me.userId, "anna")
        node.clash("anna")
    }

    @Test fun clashedNameTriesCandidates() = runBlocking<Unit> {
        clash()
        val link = node.bootstrapLink()
        val first = newGraph()
        first.restore.withPassword(link, "Anna", pw).getOrThrow() // the first candidate: one try
        assertArrayEquals(anna.me.userId, first.me.userId)
        val fetches = node.backupFetches
        val second = newGraph()
        second.restore.withPassword(link, "anna", pw2).getOrThrow()
        assertArrayEquals(boris.me.userId, second.me.userId)
        assertEquals(2, node.backupFetches - fetches) // stops at the first that decrypts
        assertEquals(RestoreError.WrongPassword, newGraph().restore.withPassword(link, "anna", "ни один не подходит").exceptionOrNull())
    }

    @Test fun hostileCandidatesInstallNothing() = runBlocking<Unit> {
        clash()
        val link = node.bootstrapLink()
        val restored = newGraph()
        // a node lists only the stranger's tag (and a different name): the typed password opens neither
        val stranger = "anna#" + tag(anna.me.userId)
        node.authAnswer = { a -> if (a.candidatesCount > 0) a.toBuilder().clearCandidates().addCandidates(stranger)
            .addCandidates("boris#" + tag(boris.me.userId)).build() else a }
        val before = node.backupAuthNames.size
        assertEquals(RestoreError.WrongPassword, restored.restore.withPassword(link, "anna", pw2).exceptionOrNull())
        assertEquals(listOf("anna", stranger), node.backupAuthNames.drop(before)) // another name is never asked about
        assertNull(restored.accounts.account.value)

        // at most MAX_CANDIDATES tries, however many the node lists
        node.authAnswer = { a -> if (a.candidatesCount > 0) a.toBuilder().clearCandidates()
            .addAllCandidates((0 until 50).map { "anna#%08x".format(it) }).build() else a }
        val fetches = node.backupFetches
        assertEquals(RestoreError.WrongPassword, restored.restore.withPassword(link, "anna", pw2).exceptionOrNull())
        assertEquals(RestoreManager.MAX_CANDIDATES, node.backupFetches - fetches)
        assertNull(restored.accounts.account.value)
    }

    @Test fun addHistoryAsksForTheTaggedName() = runBlocking<Unit> {
        clash()
        val restored = newGraph()
        restored.restore.withWords(node.bootstrapLink(), Bip39.toMnemonic(anna.seed)).getOrThrow()
        restored.restore.addHistory(pw).getOrThrow() // a plain "anna" would be CONFLICT now
        assertEquals("anna#" + tag(anna.me.userId), node.backupAuthNames.last())
        assertTrue(restored.settings.backupSecret() != null)
    }

    /**
     * D113/D114: a no_backups node is never asked for a backup directly; a direct connection abroad that freezes (D132)
     * is retried through the no_backups node's tunnel, with TLS pinned to the node that keeps the backup.
     */
    @Test fun restoreSkipsNoBackupsNodesAndRetriesThroughTheTunnel() = runBlocking<Unit> {
        history()
        node.publish(node.signManifest(2, extraHosts = listOf("ru"), noBackupsHosts = setOf("ru"), selfPin = ByteArray(32) { 5 }), 2)
        val ruId = ByteArray(16) { 1 }
        val seen = mutableListOf<List<Candidate>>()
        val restored = newGraph()
        val honest = restored.connector
        restored.connector = object : NodeConnector {
            override suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T {
                seen += candidates
                if (candidates.any { it.via == null }) throw RpcTimeoutException("backup.fetch") // the direct path froze
                return honest.withNode(candidates, block)
            }
        }
        restored.restore.withPassword(node.bootstrapLink(), "anna", pw).getOrThrow()
        assertArrayEquals(anna.me.userId, restored.me.userId)
        restored.restore.addHistory(pw).getOrThrow()
        assertEquals(4, seen.size) // each: everything first, then the tunnels only
        assertTrue(seen.flatten().none { it.via == null && it.nodeId.contentEquals(ruId) })
        for (tunnels in listOf(seen[1], seen[3])) {
            assertTrue(tunnels.isNotEmpty())
            assertTrue(tunnels.all { it.via!!.nodeId.contentEquals(ruId) && it.via!!.url.endsWith("/tunnel?to=" + node.nodeId.joinToString("") { "%02x".format(it) }) })
            assertTrue(tunnels.all { it.pins.isNotEmpty() && it.url.startsWith("wss://") })
        }
    }

    @Test fun wrongPasswordIsNotRetriedThroughTheTunnel() = runBlocking<Unit> {
        history()
        node.publish(node.signManifest(2, extraHosts = listOf("ru"), noBackupsHosts = setOf("ru"), selfPin = ByteArray(32) { 5 }), 2)
        val restored = newGraph()
        val honest = restored.connector
        var calls = 0
        restored.connector = object : NodeConnector {
            override suspend fun <T> withNode(candidates: List<Candidate>, block: suspend (NodeApi) -> T): T { calls++; return honest.withNode(candidates, block) }
        }
        val fetches = node.backupFetches
        assertEquals(RestoreError.WrongPassword, restored.restore.withPassword(node.bootstrapLink(), "anna", "не тот").exceptionOrNull())
        assertEquals(1, calls)
        assertEquals(1, node.backupFetches - fetches)
    }
}
