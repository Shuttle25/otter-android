package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.MessageRecipientEntity
import app.ottermsg.core.database.entity.MyDistributionEntity
import app.ottermsg.core.database.entity.PendingGroupEnvelopeEntity
import app.ottermsg.core.database.entity.PendingGroupUpdateEntity
import app.ottermsg.core.database.entity.SenderKeySharedEntity
import app.ottermsg.core.database.dao.ReceiptCounts
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupDaoTest {
    private lateinit var db: OtterDatabase
    private val gid = ByteArray(16) { 9 }
    private val a = ByteArray(16) { 1 }; private val b = ByteArray(16) { 2 }; private val c = ByteArray(16) { 3 }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun group(family: Boolean = true, member: Boolean = true) =
        GroupEntity(gid, "Семья", family, 1, byteArrayOf(1), null, member, a)

    @Test fun groupMembersAndConversation() = runTest {
        val g = db.groupDao()
        g.upsert(group())
        g.replaceMembers(gid, listOf(GroupMemberEntity(gid, a, true), GroupMemberEntity(gid, b, false)))
        g.replaceMembers(gid, listOf(GroupMemberEntity(gid, a, true), GroupMemberEntity(gid, c, false)))
        assertEquals(setOf(a.toList(), c.toList()), g.members(gid).map { it.userId.toList() }.toSet())
        assertEquals(1, g.isMember(gid, c)); assertEquals(0, g.isMember(gid, b))
        assertEquals(1, g.groupsIAmIn().size)

        val conv = db.conversationDao()
        val id = conv.getOrCreateGroupConversation(gid, "Семья", 5)
        assertEquals(id, conv.getOrCreateGroupConversation(gid, "Семья", 6))
        assertEquals(ConversationType.GROUP, conv.get(id)!!.type)
        assertNull(conv.get(id)!!.peerUserId)
        // a direct conversation coexists (NULL peer ids don't clash in the unique index)
        conv.getOrCreateDirect(b, "Борис", 1)
        conv.getOrCreateGroupConversation(ByteArray(16) { 8 }, "Другая", 1)
        val summaries = conv.observeSummaries().first()
        assertEquals(3, summaries.size)
        val s = summaries.first { it.id == id }
        assertTrue(s.family); assertArrayEquals(gid, s.groupId); assertArrayEquals(gid, s.seed)
        conv.setGroupTitle(gid, "Дача")
        assertEquals("Дача", conv.get(id)!!.title)
    }

    @Test fun pendingQueuesAndPrune() = runTest {
        val g = db.groupDao()
        g.insertPendingUpdate(PendingGroupUpdateEntity(groupId = gid, signed = byteArrayOf(1), fromUser = b, receivedMs = 10))
        g.insertPendingUpdate(PendingGroupUpdateEntity(groupId = gid, signed = byteArrayOf(2), fromUser = b, receivedMs = 1000))
        assertEquals(2, g.pendingUpdates(gid).size)
        assertEquals(1, g.prunePendingUpdates(cutoff = 500))
        assertEquals(1, g.pendingUpdates(gid).size)

        val e = PendingGroupEnvelopeEntity(byteArrayOf(7), gid, b, byteArrayOf(1, 2), 10)
        g.insertPendingEnvelope(e); g.insertPendingEnvelope(e) // idempotent
        g.insertPendingEnvelope(PendingGroupEnvelopeEntity(byteArrayOf(8), null, c, byteArrayOf(3), 2000))
        assertEquals(1, g.pendingEnvelopesFrom(b).size)
        assertEquals(1, g.pendingEnvelopesFor(gid).size)
        assertEquals(listOf(7.toByte()), g.pendingEnvelopesBefore(500).map { it.recordKey[0] })
        g.deletePendingEnvelope(byteArrayOf(7))
        assertTrue(g.pendingEnvelopesFrom(b).isEmpty())
    }

    @Test fun senderKeyBookkeeping() = runTest {
        val g = db.groupDao()
        val dist = ByteArray(16) { 5 }
        assertEquals(1L, g.insertMyDistribution(MyDistributionEntity(gid, dist, 1)))
        assertEquals(-1L, g.insertMyDistribution(MyDistributionEntity(gid, ByteArray(16), 2))) // keeps the first
        assertArrayEquals(dist, g.myDistribution(gid)!!.distributionId)
        g.insertShared(listOf(SenderKeySharedEntity(gid, dist, b), SenderKeySharedEntity(gid, dist, c)))
        assertEquals(2, g.sharedWith(gid, dist).size)
        g.deleteMyDistribution(gid); g.clearShared(gid)
        assertNull(g.myDistribution(gid)); assertTrue(g.sharedWith(gid, dist).isEmpty())
    }

    @Test fun perRecipientReceipts() = runTest {
        db.contactDao().upsert(ContactEntity(userId = b, username = "boris", displayName = "Борис", accountPub = ByteArray(32),
            identityPub = ByteArray(33), verified = false, identityChanged = false, revoked = false, entryVersion = 1))
        val cid = db.conversationDao().getOrCreateGroupConversation(gid, "Семья", 1)
        val local = db.messageDao().insert(MessageEntity(messageId = ByteArray(16) { 4 }, conversationId = cid, senderId = a, outgoing = true,
            sentMs = 1, receivedMs = 1, body = "привет", status = MessageStatus.SENT))
        val r = db.messageRecipientDao()
        r.insertAll(listOf(MessageRecipientEntity(local, b), MessageRecipientEntity(local, c)))
        assertEquals(ReceiptCounts(2, 0, 0), r.counts(local))
        assertEquals(1, r.setDelivered(local, b, 10))
        assertEquals(0, r.setDelivered(local, b, 11))              // first time wins
        assertEquals(0, r.setDelivered(local, ByteArray(16), 10))  // not in the frozen set
        assertEquals(ReceiptCounts(2, 1, 0), r.counts(local))
        assertEquals(1, r.setRead(local, c, 20))                    // read implies delivered
        assertEquals(ReceiptCounts(2, 2, 1), r.counts(local))
        val info = r.observeRecipients(local).first().associateBy { it.userId.toList() }
        assertEquals("Борис", info[b.toList()]!!.displayName)
        assertEquals(20L, info[c.toList()]!!.deliveredMs)
    }
}
