package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Announcement
import app.ottermsg.proto.v1.AnnouncementDeleted
import app.ottermsg.proto.v1.AppUpdate
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class AnnouncementVerifierTest {
    private fun newKey() = Ed25519.keyPairFromSeed(ByteArray(32).also(SecureRandom()::nextBytes))
    private val key = newKey()
    private val other = newKey()
    private val keys = listOf(key.publicKey)

    private fun rec(type: RecordType, payload: ByteArray, k: Ed25519.KeyPair = key, expiresMs: Long = 0): Record =
        RecordCodec.sign(RecordCodec.body(type, Ids.id16(k.publicKey), payload, expiresMs = expiresMs), k)

    private fun ann(ru: String = "", en: String = "", update: AppUpdate? = null): ByteArray =
        Announcement.newBuilder().setTextRu(ru).setTextEn(en).apply { update?.let { setUpdate(it) } }.build().toByteArray()

    private fun upd(name: String = "0.6.0", code: Int = 6, sha: Int = 32, size: Long = 50L shl 20): AppUpdate =
        AppUpdate.newBuilder().setVersionName(name).setVersionCode(code).setSha256(ByteString.copyFrom(ByteArray(sha))).setSize(size).build()

    @Test fun goodAnnouncement() {
        val r = rec(RecordType.ANNOUNCEMENT, ann(ru = "Привет"))
        val n = AnnouncementVerifier.verify(r, keys) as News.Item
        assertEquals("Привет", n.announcement.textRu)
        assertEquals(32, n.key.size)
        assertTrue(n.author.contentEquals(Ids.id16(key.publicKey)))
    }

    @Test fun goodVariants() {
        for (p in listOf(ann(en = "Hello"), ann("Привет", "Hello"), ann("Обновление", update = upd()),
            ann(ru = "я".repeat(4000)), ann(ru = "x", update = upd(name = "1".repeat(32), size = 100L shl 20)))) {
            assertTrue(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, p), keys) is News.Item)
        }
    }

    @Test fun goodDeletion() {
        val r = rec(RecordType.ANNOUNCEMENT_DELETED, AnnouncementDeleted.newBuilder().setTarget(ByteString.copyFrom(ByteArray(32))).build().toByteArray())
        val d = AnnouncementVerifier.verify(r, keys) as News.Deleted
        assertEquals(32, d.target.size)
    }

    @Test fun rejects() {
        val ok = ann(ru = "x")
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, ok, other), keys))
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, ok), emptyList()))
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ENVELOPE, ok), keys))
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, ann(ru = "  ", en = "\n")), keys))
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, ann()), keys))
        val tampered = rec(RecordType.ANNOUNCEMENT, ok).toBuilder().setSignature(ByteString.copyFrom(ByteArray(64))).build()
        assertNull(AnnouncementVerifier.verify(tampered, keys))
    }

    @Test fun rejectsBadPayloads() {
        val bad = listOf(
            ann(ru = "я".repeat(4001)),
            ann(ru = "x", update = upd(name = "")),
            ann(ru = "x", update = upd(code = 0)),
            ann(ru = "x", update = upd(sha = 31)),
            ann(ru = "x", update = upd(size = 0)),
            ann(ru = "x", update = upd(size = (100L shl 20) + 1)),
            ann(ru = "x", update = upd(size = -1)), // uint64 max
            ann(ru = "x", update = upd(name = "1".repeat(33))),
            invalidUtf8(),
            byteArrayOf(0x0a, 0x7f), // truncated
        )
        for ((i, p) in bad.withIndex()) assertNull("bad $i", AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, p), keys))
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT, ann(ru = "x"), expiresMs = System.currentTimeMillis() + 3_600_000), keys))
        val short = AnnouncementDeleted.newBuilder().setTarget(ByteString.copyFrom(ByteArray(31))).build().toByteArray()
        assertNull(AnnouncementVerifier.verify(rec(RecordType.ANNOUNCEMENT_DELETED, short), keys))
    }

    /** text_en = 0xff, written by hand so the builder cannot reject it. */
    private fun invalidUtf8(): ByteArray = byteArrayOf(0x12, 0x01, 0xff.toByte())
}
