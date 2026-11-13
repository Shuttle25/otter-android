package app.ottermsg.core.data.calls

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.model.MessageKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallLinesTest {
    private val clock = TestClock()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
    private val lines = CallLines(db, clock)
    private val peer = Peer.of(byteArrayOf(1, 2, 3), "Анна")

    @After fun tearDown() { db.close() }

    @Test fun missedLineIsUnreadOthersAreNot() = runBlocking {
        val cid = lines.write(peer, CallOutcome.MISSED, 0, atMs = 1_000)
        val conv = db.conversationDao().get(cid)!!
        assertEquals("Анна", conv.title)
        assertEquals(1, conv.unread)
        assertTrue(conv.lastActivityMs > 0)

        assertEquals(cid, lines.write(peer, CallOutcome.OUTGOING, 192, atMs = 2_000))
        assertEquals(1, db.conversationDao().get(cid)!!.unread)

        val msgs = db.messageDao().all()
        assertEquals(2, msgs.size)
        assertTrue(msgs.all { it.kind == MessageKind.CALL && it.readReceiptSent && it.body.isEmpty() })
        assertFalse(msgs[0].outgoing); assertEquals(CallOutcome.MISSED.code, msgs[0].callOutcome)
        assertTrue(msgs[1].outgoing); assertEquals(CallOutcome.OUTGOING.code, msgs[1].callOutcome); assertEquals(192, msgs[1].callDurationS)
        assertEquals(2_000, msgs[1].sentMs)
        assertTrue("never read-receipted", db.messageDao().unreadReceiptsPending(cid).isEmpty())
    }
}
