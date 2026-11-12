package app.ottermsg.core.data

import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.proto.v1.Content
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D53: SessionReset after a (simulated) restore and after undecryptable messages; group delivery recovers. */
@RunWith(RobolectricTestRunner::class)
class SessionResetTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val anna = TestGraph(node, clock)
    private val boris = TestGraph(node, clock)

    @After fun tearDown() { anna.close(); boris.close() }

    private suspend fun family() {
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow(); anna.online()
        boris.register(anna.invites.create().link, "Борис", "boris").getOrThrow(); boris.online()
        eventually(what = "anna knows boris") { anna.db.contactDao().get(boris.me.userId) != null }
    }

    private suspend fun TestGraph.thread(peer: TestGraph): List<MessageEntity> =
        messages.observeMessages(messages.conversationWith(peer.me.userId)).first()
    private suspend fun TestGraph.texts(peer: TestGraph) = thread(peer).filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun TestGraph.groupTexts(gid: ByteArray) =
        db.conversationDao().byGroup(gid)?.let { db.messageDao().observe(it.id).first() }.orEmpty()
            .filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun TestGraph.resetsQueued(): Int = db.outboxDao().due(Long.MAX_VALUE, 1000)
        .count { runCatching { Content.parseFrom(it.contentBytes).hasSessionReset() }.getOrDefault(false) }

    /** What a restore looks like to the others: same identity, no Signal sessions or sender keys, a newer install. */
    private suspend fun TestGraph.wipeSignalState() {
        offline()
        db.openHelper.writableDatabase.execSQL("DELETE FROM signal_kv")
        db.openHelper.writableDatabase.execSQL("DELETE FROM sender_key_shared")
    }

    @Test fun resetRestoresDirectAndGroupDelivery() = runBlocking<Unit> {
        family()
        val cid = anna.messages.conversationWith(boris.me.userId)
        anna.messages.sendText(cid, "привет")
        eventually(what = "boris reads привет") { "привет" in boris.texts(anna) }
        val gcid = anna.groups.create("Семья", true, listOf(boris.me.userId))
        val gid = checkNotNull(anna.db.conversationDao().get(gcid)!!.groupId)
        anna.messages.sendText(gcid, "всем")
        eventually(what = "boris reads всем") { "всем" in boris.groupTexts(gid) }
        eventually(what = "boris's mailbox drained") { node.mailbox(boris.me.userId).isEmpty() }

        boris.wipeSignalState()
        boris.settings.setRestorePending(true)
        boris.settings.setInstalledMs(clock.now())
        boris.online() // InstallGuard: new prekeys (newer install) + SessionReset to anna

        eventually(what = "anna sees the reset") { anna.thread(boris).any { it.kind == MessageKind.SYSTEM_RESET } }
        assertEquals(false, boris.settings.restorePending())
        assertEquals(boris.settings.installedMs(), node.loginInfo(boris.me.userId).second)
        anna.messages.sendText(cid, "снова")
        eventually(what = "boris reads снова") { "снова" in boris.texts(anna) }
        anna.messages.sendText(gcid, "и в группе")
        eventually(what = "boris reads the group message") { "и в группе" in boris.groupTexts(gid) }
    }

    @Test fun undecryptableMessageTriggersResetAndRecovers() = runBlocking<Unit> {
        family()
        val cid = anna.messages.conversationWith(boris.me.userId)
        anna.messages.sendText(cid, "раз")
        eventually(what = "boris reads раз") { "раз" in boris.texts(anna) }
        boris.signal.withSessions { store, s -> store.deleteSession(s.address(anna.me.userId)) } // boris lost the session
        anna.messages.sendText(cid, "два") // a normal Signal message boris can't read
        eventually(what = "boris shows an undecryptable message") { boris.thread(anna).any { it.kind == MessageKind.SYSTEM_UNDECRYPTABLE } }
        eventually(what = "anna gets the reset") { anna.thread(boris).any { it.kind == MessageKind.SYSTEM_RESET } }
        anna.messages.sendText(cid, "три")
        eventually(what = "boris reads три") { "три" in boris.texts(anna) }
    }

    @Test fun resetsAreThrottledPerHour() = runBlocking<Unit> {
        family()
        boris.offline() // keep the queue: nothing is sent while offline
        boris.resets.onUndecryptable(anna.me.userId)
        boris.resets.onUndecryptable(anna.me.userId)
        assertEquals(1, boris.resetsQueued())
        clock.advance(61 * 60_000L)
        boris.resets.onUndecryptable(anna.me.userId)
        assertEquals(2, boris.resetsQueued())
        assertTrue(boris.db.contactDao().get(anna.me.userId)!!.resetMs > 0)
    }
}
