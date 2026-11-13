package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewsDaoTest {
    private lateinit var db: OtterDatabase
    private val author = ByteArray(16) { 7 }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun news(id: Byte, cid: Long, kind: Int = MessageKind.NEWS) = MessageEntity(messageId = ByteArray(32) { id }, conversationId = cid,
        senderId = author, outgoing = false, sentMs = id.toLong(), receivedMs = 1, body = "n$id", status = MessageStatus.DELIVERED, kind = kind)

    @Test fun newsConversationIsCreatedOnce() = runTest {
        val c = db.conversationDao()
        assertNull(c.news())
        val a = c.getOrCreateNews(1)
        assertEquals(a, c.getOrCreateNews(2))
        assertEquals(ConversationType.NEWS, c.get(a)!!.type)
        assertEquals("", c.get(a)!!.title)
        assertTrue(c.observeSummaries().first().isEmpty()) // no news message yet
        db.messageDao().insert(news(1, a))
        assertEquals(ConversationType.NEWS, c.observeSummaries().first().single().type)
    }

    @Test fun emptyNewsChatIsHiddenFromTheChatList() = runTest {
        val c = db.conversationDao()
        val cid = c.getOrCreateNews(1)
        db.messageDao().insert(news(1, cid))
        val direct = c.getOrCreateDirect(ByteArray(16) { 9 }, "Аня", 2) // an empty direct chat stays listed
        assertEquals(listOf(direct, cid), c.observeSummaries().first().map { it.id })
        db.messageDao().deleteNews(ByteArray(32) { 1 }) // deleted or revoked: nothing left to show
        assertEquals(listOf(direct), c.observeSummaries().first().map { it.id })
    }

    @Test fun deleteNewsTouchesOnlyNews() = runTest {
        val cid = db.conversationDao().getOrCreateNews(1)
        db.messageDao().insert(news(1, cid)); db.messageDao().insert(news(2, cid)); db.messageDao().insert(news(3, cid, MessageKind.TEXT))
        assertEquals(2, db.messageDao().allNews().size)
        assertEquals(1, db.messageDao().deleteNews(ByteArray(32) { 1 }))
        assertEquals(0, db.messageDao().deleteNews(ByteArray(32) { 3 })) // not a news message
        assertEquals(2, db.messageDao().count(cid))
        db.conversationDao().setUnread(cid, 5)
        assertEquals(5, db.conversationDao().get(cid)!!.unread)
    }

    @Test fun newsNeverNeedsReadReceipts() = runTest {
        val cid = db.conversationDao().getOrCreateNews(1)
        db.messageDao().insert(news(1, cid))
        assertTrue(db.messageDao().unreadReceiptsPending(cid).isEmpty())
    }
}
