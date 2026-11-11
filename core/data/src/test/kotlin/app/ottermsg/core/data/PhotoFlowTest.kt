package app.ottermsg.core.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.media.BlobMetaBuilder
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFlowTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val graphs = mutableListOf<TestGraph>()
    private fun graph() = TestGraph(node, clock).also { graphs += it }
    private val a = graph()
    private val b = graph()

    @After fun tearDown() { graphs.forEach { it.close() } }

    /** A JPEG on disk; [noise] makes it big enough for several 256 KiB chunks. */
    private fun picture(w: Int = 800, h: Int = 600, noise: Boolean = false): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (noise) {
            val r = Random(7)
            val px = IntArray(w * h) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        } else bmp.eraseColor(Color.rgb(30, 140, 120))
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(ctx.cacheDir, "pick-${Random.nextInt()}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
    }

    private suspend fun family(policy: Int = Settings.AUTO_ANY) {
        a.register(node.bootstrapLink(), "Анна", "anna").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Борис", "boris").getOrThrow()
        b.settings.setPhotoAutoDownload(policy)
        b.online()
        eventually(what = "A sees B") { a.db.contactDao().get(b.me.userId) != null }
    }

    private suspend fun TestGraph.lastIncoming() =
        db.messageDao().observe(messages.conversationWith(a.me.userId)).first().lastOrNull { !it.outgoing }

    @Test fun sendDownloadDecryptAck() = runBlocking {
        family()
        val src = picture()
        val cid = a.messages.conversationWith(b.me.userId)
        a.messages.setDraft(cid, "черновик")
        val local = a.messages.sendPhoto(cid, src, "  Дача ")!!
        assertTrue("source copy deleted", !src.exists())
        assertEquals("", a.db.conversationDao().get(cid)!!.draft)
        val m = a.db.messageDao().get(local)!!
        assertEquals(MessageKind.PHOTO, m.kind); assertEquals("Дача", m.body)
        eventually(what = "B receives the photo") { b.lastIncoming()?.kind == MessageKind.PHOTO }
        val inMsg = b.lastIncoming()!!
        assertEquals("Дача", inMsg.body)
        assertNotNull(b.db.attachmentDao().forMessage(inMsg.localId)!!.thumb)
        eventually(what = "B downloads") { b.db.attachmentDao().forMessage(inMsg.localId)!!.state == AttachmentState.DOWNLOADED }
        assertArrayEquals(a.photoLoader.jpeg(local), b.photoLoader.jpeg(inMsg.localId))
        assertNotNull(b.photos.load(inMsg.localId, 200))
        val key = a.db.attachmentDao().forMessage(local)!!.blobKey
        eventually(what = "node deletes the blob after B's ack") { node.blobGone(key) }
        eventually(what = "A sees DELIVERED") { a.db.messageDao().get(local)!!.status >= MessageStatus.DELIVERED }
    }

    @Test fun offlineSendUploadsBeforeTheEnvelope() = runBlocking {
        family()
        a.offline()
        val cid = a.messages.conversationWith(b.me.userId)
        val local = a.messages.sendPhoto(cid, picture(), "")!!
        assertEquals(MessageStatus.PENDING, a.db.messageDao().get(local)!!.status)
        assertEquals(AttachmentState.UPLOADING, a.db.attachmentDao().forMessage(local)!!.state)
        assertTrue("held: nothing due", a.db.outboxDao().due(Long.MAX_VALUE).none { it.messageLocalId == local })
        a.online()
        eventually(what = "uploaded") { a.db.attachmentDao().forMessage(local)!!.state == AttachmentState.UPLOADED }
        eventually(what = "B gets it") { b.lastIncoming()?.kind == MessageKind.PHOTO }
    }

    @Test fun quotaFailsAndRetryRepublishes() = runBlocking {
        family()
        node.failBlobPut = Status.STATUS_QUOTA
        val cid = a.messages.conversationWith(b.me.userId)
        val local = a.messages.sendPhoto(cid, picture(), "")!!
        eventually(what = "FAILED") { a.db.messageDao().get(local)!!.status == MessageStatus.FAILED }
        assertEquals(AttachmentState.UPLOAD_FAILED, a.db.attachmentDao().forMessage(local)!!.state)
        assertTrue(a.db.outboxDao().forMessage(local).isEmpty())
        val oldMeta = a.db.attachmentDao().forMessage(local)!!.metaRecord!!
        node.failBlobPut = null
        assertTrue(a.messages.retry(local))
        val newMeta = a.db.attachmentDao().forMessage(local)!!.metaRecord!!
        assertArrayEquals("same record id (blob key)", app.ottermsg.proto.v1.RecordBody.parseFrom(Record.parseFrom(oldMeta).body).id.toByteArray(),
            app.ottermsg.proto.v1.RecordBody.parseFrom(Record.parseFrom(newMeta).body).id.toByteArray())
        eventually(what = "delivered after retry") { b.lastIncoming()?.kind == MessageKind.PHOTO }
        eventually(what = "B downloads") { b.db.attachmentDao().forMessage(b.lastIncoming()!!.localId)!!.state == AttachmentState.DOWNLOADED }
    }

    @Test fun manualPolicyWaitsForTapAndGoneWhenDeleted() = runBlocking {
        family(policy = Settings.AUTO_MANUAL)
        val cid = a.messages.conversationWith(b.me.userId)
        a.messages.sendPhoto(cid, picture(), "")
        eventually(what = "B receives") { b.lastIncoming()?.kind == MessageKind.PHOTO }
        val id = b.lastIncoming()!!.localId
        b.media.drainOnce(b.session!!)
        assertEquals(AttachmentState.REMOTE, b.db.attachmentDao().forMessage(id)!!.state)
        node.failBlobGet = Status.STATUS_NOT_FOUND
        b.photos.download(id)
        eventually(what = "GONE") { b.db.attachmentDao().forMessage(id)!!.state == AttachmentState.GONE }
        assertNull(b.photos.load(id, 200))
    }

    @Test fun wifiOnlyWaitsForUnmetered() = runBlocking {
        family(policy = Settings.AUTO_WIFI)
        b.unmetered = false
        val cid = a.messages.conversationWith(b.me.userId)
        a.messages.sendPhoto(cid, picture(), "")
        eventually(what = "B receives") { b.lastIncoming()?.kind == MessageKind.PHOTO }
        val id = b.lastIncoming()!!.localId
        b.media.drainOnce(b.session!!)
        assertEquals(AttachmentState.REMOTE, b.db.attachmentDao().forMessage(id)!!.state)
        b.unmetered = true
        b.media.kick()
        eventually(what = "downloads on Wi-Fi") { b.db.attachmentDao().forMessage(id)!!.state == AttachmentState.DOWNLOADED }
    }

    @Test fun groupOfThreeSharesOneBlob() = runBlocking {
        family()
        val c = graph()
        c.register(a.invites.create().link, "Вера", "vera").getOrThrow()
        c.settings.setPhotoAutoDownload(Settings.AUTO_ANY)
        c.online()
        eventually(what = "A sees C") { a.db.contactDao().get(c.me.userId) != null }
        val cid = a.groups.create("Семья", true, listOf(b.me.userId, c.me.userId))
        val gid = a.db.conversationDao().get(cid)!!.groupId!!
        for (x in listOf(b, c)) eventually(what = "group reaches a member") { x.db.groupDao().get(gid)?.version == 1L }
        val local = a.messages.sendPhoto(cid, picture(), "все вместе")!!
        val key = a.db.attachmentDao().forMessage(local)!!.blobKey
        eventually(what = "blob announced") { node.blobReaders(key) == 2 }
        for (x in listOf(b, c)) eventually(what = "member downloads") {
            val conv = x.db.conversationDao().byGroup(gid) ?: return@eventually false
            val m = x.db.messageDao().observe(conv.id).first().lastOrNull { it.kind == MessageKind.PHOTO } ?: return@eventually false
            x.db.attachmentDao().forMessage(m.localId)?.state == AttachmentState.DOWNLOADED
        }
        eventually(what = "blob gone after both acks") { node.blobGone(key) }
        assertTrue(node.blobKeys().isEmpty())
    }

    @Test fun restartMidUploadResumesFromMissing() = runBlocking {
        family()
        a.offline()
        val cid = a.messages.conversationWith(b.me.userId)
        val local = a.messages.sendPhoto(cid, picture(2000, 1500, noise = true), "")!!
        val att = a.db.attachmentDao().forMessage(local)!!
        val chunks = ((att.size + 262143) / 262144).toInt()
        assertTrue("several chunks ($chunks)", chunks >= 3)
        // an earlier session got as far as chunk 0
        val s = node.connect()
        s.login(a.me.userId, a.me.accountKey)
        s.blobCreate(Record.parseFrom(att.metaRecord))
        val file = a.files.file(att.file!!)
        s.blobPut(att.blobKey, 0, file.readBytes().copyOfRange(0, 262144))
        s.close()
        val a2 = a.restart().also { graphs += it }
        a2.online()
        eventually(what = "uploaded after restart") { a2.db.attachmentDao().forMessage(local)!!.state == AttachmentState.UPLOADED }
        assertEquals("chunk 0 not uploaded again", chunks, node.blobPuts)
        eventually(what = "B downloads") {
            val m = b.lastIncoming() ?: return@eventually false
            b.db.attachmentDao().forMessage(m.localId)?.state == AttachmentState.DOWNLOADED
        }
        assertArrayEquals(BlobMetaBuilder.blobKey(a.me.userId, app.ottermsg.proto.v1.RecordBody.parseFrom(Record.parseFrom(att.metaRecord).body).id.toByteArray()), att.blobKey)
    }

    @Test fun deleteLocalRemovesTheFile() = runBlocking {
        family()
        a.offline()
        val cid = a.messages.conversationWith(b.me.userId)
        val local = a.messages.sendPhoto(cid, picture(), "")!!
        val f = a.files.file(a.db.attachmentDao().forMessage(local)!!.file!!)
        assertTrue(f.exists())
        a.messages.deleteLocal(local)
        assertTrue(!f.exists())
        assertNull(a.db.attachmentDao().forMessage(local))
        assertTrue(a.db.outboxDao().forMessage(local).isEmpty())
    }

    @Test fun unreadableSourceReturnsNull() = runBlocking {
        family()
        val junk = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "junk.jpg").apply { writeText("x") }
        assertNull(a.messages.sendPhoto(a.messages.conversationWith(b.me.userId), junk, ""))
    }
}
