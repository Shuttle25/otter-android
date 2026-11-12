package app.ottermsg.core.data

import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.database.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "ты вернулась!")
        eventually(what = "restored anna reads it") { "ты вернулась!" in restored.texts(boris.me.userId) }
        restored.messages.sendText(restored.messages.conversationWith(boris.me.userId), "да")
        eventually(what = "boris reads да") { "да" in boris.texts(anna.me.userId) }
        boris.messages.sendText(boris.db.conversationDao().byGroup(gid)!!.id, "в группе от бориса")
        eventually(what = "restored anna reads the group") { "в группе от бориса" in restored.groupTexts(gid) }
        restored.messages.sendText(restored.db.conversationDao().byGroup(gid)!!.id, "в группе от анны")
        eventually(what = "boris reads the group") { "в группе от анны" in boris.groupTexts(gid) }
        eventually(what = "restored phone backs up with the same password") { node.backupOf(anna.me.userId)!!.version == 2L }

        anna.online() // the old phone comes back
        eventually(what = "old phone superseded") { anna.settings.superseded() }
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
}
