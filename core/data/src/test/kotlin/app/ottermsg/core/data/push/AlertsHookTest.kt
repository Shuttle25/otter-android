package app.ottermsg.core.data.push

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.data.eventually
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** D62 hooks: receivers report stored visible messages, the repository reports reads; receipts/system events never alert. */
@RunWith(RobolectricTestRunner::class)
class AlertsHookTest {
    private class Recording : MessageAlerts {
        val incoming: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val read: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        override suspend fun onIncoming(conversationId: Long) { incoming += conversationId }
        override fun onRead(conversationId: Long) { read += conversationId }
    }

    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val aAlerts = Recording()
    private val bAlerts = Recording()
    private val cAlerts = Recording()
    private val a = TestGraph(node, clock, alerts = aAlerts)
    private val b = TestGraph(node, clock, alerts = bAlerts)
    private val c = TestGraph(node, clock, alerts = cAlerts)

    @After fun tearDown() { a.close(); b.close(); c.close() }

    @Test fun directAndGroupMessagesAlertReceiptsDoNot() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow()
        b.online()
        c.register(a.invites.create().link, "Вера", "vera").getOrThrow()
        c.online()
        // M9.5 (D135): B and C are siblings (both invited by A): they learn each other from the group below
        eventually(what = "inviter and invitees know each other") {
            listOf(b, c).all { y -> a.db.contactDao().get(y.me.userId) != null && y.db.contactDao().get(a.me.userId)?.entryVersion?.let { it > 0 } == true }
        }

        // Direct: A → B "hi"
        val local = a.messages.sendText(a.messages.conversationWith(b.me.userId), "hi")!!
        eventually(what = "B alerted") { bAlerts.incoming.isNotEmpty() }
        val bCid = b.messages.conversationWith(a.me.userId)
        assertEquals(listOf(bCid), bAlerts.incoming.toList())

        b.messages.markConversationRead(bCid)
        assertEquals(listOf(bCid), bAlerts.read.toList())
        eventually(what = "A sees READ") { a.db.messageDao().get(local)!!.status == app.ottermsg.core.database.model.MessageStatus.READ }
        // A got DELIVERED + READ receipts: no alert for them.
        assertEquals(emptyList<Long>(), aAlerts.incoming.toList())

        // Group: A creates {A,B,C} (system event, no alert), then sends a text (alerts B and C once each).
        val gCid = a.groups.create("Семья", true, listOf(b.me.userId, c.me.userId))
        val gid = a.db.conversationDao().get(gCid)!!.groupId!!
        eventually(what = "group reaches C") { c.db.conversationDao().byGroup(gid) != null }
        assertEquals(emptyList<Long>(), cAlerts.incoming.toList())
        a.messages.sendText(gCid, "всем привет")
        eventually(what = "C alerted") { cAlerts.incoming.isNotEmpty() }
        eventually(what = "B alerted twice") { bAlerts.incoming.size == 2 }
        assertEquals(listOf(c.db.conversationDao().byGroup(gid)!!.id), cAlerts.incoming.toList())
        assertEquals(b.db.conversationDao().byGroup(gid)!!.id, bAlerts.incoming[1])
        assertEquals(emptyList<Long>(), aAlerts.incoming.toList())
    }
}
