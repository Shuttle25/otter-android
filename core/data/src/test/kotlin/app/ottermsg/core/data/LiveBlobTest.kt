package app.ottermsg.core.data

import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.data.media.BlobMetaBuilder
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.database.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import kotlin.random.Random

/**
 * M4 acceptance against real nodes: a 20 MiB blob from anna to boris through MediaTransfers (no JPEG step).
 * Skipped unless OTTER_DEV_INVITE holds a fresh bootstrap link. Optional OTTER_PREFER_A / OTTER_PREFER_B put the two
 * clients on given nodes (e.g. a and c, so the blob has to cross the mesh):
 *   OTTER_DEV_INVITE="otter://join/…" OTTER_PREFER_A=a OTTER_PREFER_B=c ./gradlew :core:data:testDebugUnitTest --tests '*LiveBlobTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LiveBlobTest {
    @Test fun twentyMiBReachesTheReader() = runBlocking {
        val invite = System.getenv("OTTER_DEV_INVITE")
        assumeTrue("set OTTER_DEV_INVITE to a fresh bootstrap link", !invite.isNullOrBlank())
        val suffix = (1000..9999).random()
        val anna = LiveClient(); val boris = LiveClient()
        try {
            anna.register(invite!!, "Анна", "anna_$suffix")
            anna.online(System.getenv("OTTER_PREFER_A"))
            boris.register(anna.invites.create().link, "Борис", "boris_$suffix")
            boris.online(System.getenv("OTTER_PREFER_B"))
            eventually(timeoutMs = 30_000, what = "anna sees boris") { anna.db.contactDao().get(boris.me.userId) != null }

            val plain = Random.nextBytes(20 shl 20)
            val id = ByteArray(16).also(SecureRandom()::nextBytes)
            val key = BlobCipher.newKey()
            val file = anna.files.file(anna.files.newName())
            val sealed = BlobCipher.encrypt(plain.inputStream(), file, key, BlobMetaBuilder.blobKey(anna.me.userId, id))
            val cid = anna.messages.conversationWith(boris.me.userId)
            anna.messages.sendEncryptedFile(cid, file, key, id, sealed.sha256)

            val bc = boris.messages.conversationWith(anna.me.userId)
            eventually(timeoutMs = 300_000, what = "boris downloads 20 MiB") {
                val m = boris.db.messageDao().observe(bc).first().lastOrNull { it.kind == MessageKind.PHOTO } ?: return@eventually false
                boris.db.attachmentDao().forMessage(m.localId)?.state == AttachmentState.DOWNLOADED
            }
            val m = boris.db.messageDao().observe(bc).first().last { it.kind == MessageKind.PHOTO }
            val a = boris.db.attachmentDao().forMessage(m.localId)!!
            val got = BlobCipher.decrypt(boris.files.file(a.file!!), a.key, a.blobKey).use { it.readBytes() }
            assertArrayEquals(plain, got)
            eventually(timeoutMs = 60_000, what = "boris acked") { boris.db.attachmentDao().forMessage(m.localId)?.ackPending == false }
        } finally {
            anna.close(); boris.close()
        }
    }
}
