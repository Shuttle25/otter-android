package app.ottermsg.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.backup.SnapshotCodec
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.MessageRecipientEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnapshotCodecTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()

    @After fun tearDown() { a.close(); fresh.close() }

    private fun id(n: Int) = ByteArray(16) { n.toByte() }

    @Test fun exportImportRoundTrip() = runBlocking<Unit> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val db = a.db
        val boris = id(2); val vera = id(3); val me = a.me.userId
        db.contactDao().upsert(ContactEntity(boris, "boris", "Борис", id(20), ByteArray(33) { 5 }, verified = true, identityChanged = false, revoked = false, entryVersion = 3))
        db.contactDao().upsert(ContactEntity(vera, "vera", "Вера", id(30), ByteArray(33) { 6 }, verified = false, identityChanged = true, revoked = false, entryVersion = 1))
        // conversation ids start high so the import has to remap them
        val direct = db.conversationDao().insert(ConversationEntity(id = 40, peerUserId = boris, title = "Борис", lastActivityMs = 100, unread = 2, draft = "черн"))
        val gid = id(9)
        val group = db.conversationDao().insert(ConversationEntity(id = 41, title = "Семья", lastActivityMs = 200, groupId = gid, type = ConversationType.GROUP))
        db.messageDao().insert(MessageEntity(messageId = id(50), conversationId = direct, senderId = me, outgoing = true, sentMs = 1, receivedMs = 1, body = "привет", status = MessageStatus.READ))
        db.messageDao().insert(MessageEntity(messageId = id(51), conversationId = direct, senderId = boris, outgoing = false, sentMs = 2, receivedMs = 3, body = "и тебе", status = MessageStatus.DELIVERED, readReceiptSent = true))
        val photo = db.messageDao().insert(MessageEntity(messageId = id(52), conversationId = direct, senderId = boris, outgoing = false, sentMs = 4, receivedMs = 5, body = "кот", status = MessageStatus.DELIVERED, kind = MessageKind.PHOTO))
        db.attachmentDao().insert(AttachmentEntity(messageLocalId = photo, blobKey = ByteArray(32) { 1 }, key = ByteArray(32) { 2 }, digest = ByteArray(32) { 3 },
            size = 1000, mime = "image/jpeg", width = 640, height = 480, thumb = byteArrayOf(9, 9, 9), file = "x.bin", state = AttachmentState.DOWNLOADED))
        db.messageDao().insert(MessageEntity(messageId = id(53), conversationId = direct, senderId = me, outgoing = true, sentMs = 6, receivedMs = 6, body = "не ушло", status = MessageStatus.PENDING))
        val gm = db.messageDao().insert(MessageEntity(messageId = id(54), conversationId = group, senderId = me, outgoing = true, sentMs = 7, receivedMs = 7, body = "всем", status = MessageStatus.SENT))
        db.messageRecipientDao().insertAll(listOf(MessageRecipientEntity(gm, boris, deliveredMs = 8), MessageRecipientEntity(gm, vera)))
        db.groupDao().upsert(GroupEntity(gid, "Семья", family = true, version = 2, stateSigned = byteArrayOf(1, 2, 3), prevState = null, iAmMember = true, createdBy = me))
        db.groupDao().replaceMembers(gid, listOf(GroupMemberEntity(gid, me, true), GroupMemberEntity(gid, boris, false), GroupMemberEntity(gid, vera, false)))
        val news = db.conversationDao().getOrCreateNews(300) // never backed up: news.list refills it (D67)
        db.messageDao().insert(MessageEntity(messageId = ByteArray(32) { 60 }, conversationId = news, senderId = id(61), outgoing = false, sentMs = 9, receivedMs = 9, body = "новость", status = MessageStatus.DELIVERED, kind = MessageKind.NEWS))
        a.settings.setPhotoAutoDownload(Settings.AUTO_MANUAL); a.settings.setPhotoSaveToGallery(true); a.settings.setEntryVersion(4)

        val snap = SnapshotCodec.gunzip(SnapshotCodec.gzip(SnapshotCodec.export(db, a.me, a.settings, 999)).inputStream())
        assertArrayEquals(a.seed, snap.seed.toByteArray())
        assertEquals("mama", snap.username); assertEquals("Мама", snap.displayName); assertEquals(4, snap.entryVersion)
        assertEquals(a.me.createdMs, snap.registeredMs)
        assertEquals(0, snap.conversationsList.count { it.type == ConversationType.NEWS })
        assertEquals(0, snap.messagesList.count { it.kind == MessageKind.NEWS })

        val settings = Settings(fresh.settingsDao())
        SnapshotCodec.import(snap, fresh, settings)
        assertEquals(2, fresh.contactDao().all().size)
        assertEquals(true, fresh.contactDao().get(vera)!!.identityChanged)
        val convs = fresh.conversationDao().all()
        assertEquals(2, convs.size)
        val d = convs.single { it.peerUserId?.contentEquals(boris) == true }
        assertEquals("черн", d.draft); assertEquals(2, d.unread)
        val msgs = fresh.messageDao().all()
        assertEquals(listOf("привет", "и тебе", "кот", "не ушло", "всем"), msgs.map { it.body })
        assertEquals(d.id, msgs[0].conversationId)
        assertEquals(MessageStatus.FAILED, msgs[3].status) // unsent: no outbox in the backup
        assertEquals(true, msgs[1].readReceiptSent)
        val att = fresh.attachmentDao().forMessage(msgs[2].localId)!!
        assertEquals(AttachmentState.NOT_IN_BACKUP, att.state); assertNull(att.file)
        assertArrayEquals(byteArrayOf(9, 9, 9), att.thumb); assertEquals(640, att.width)
        val rs = fresh.messageRecipientDao().forMessage(msgs[4].localId)
        assertEquals(2, rs.size); assertEquals(8L, rs.single { it.userId.contentEquals(boris) }.deliveredMs)
        assertNull(rs.single { it.userId.contentEquals(vera) }.deliveredMs)
        val g = fresh.groupDao().get(gid)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), g.stateSigned); assertEquals(2L, g.version); assertNull(g.prevState)
        assertEquals(3, fresh.groupDao().members(gid).size)
        assertEquals(convs.single { it.groupId != null }.id, msgs[4].conversationId)
        assertEquals(Settings.AUTO_MANUAL, settings.photoAutoDownload()); assertEquals(true, settings.photoSaveToGallery())
        assertEquals(4, settings.entryVersion())
    }
}
