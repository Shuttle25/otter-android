package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Announcement
import app.ottermsg.proto.v1.AnnouncementDeleted
import app.ottermsg.proto.v1.AppUpdate
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException

/** A verified news record (D66). [key] = record key author ‖ id. */
sealed interface News {
    val key: ByteArray
    val author: ByteArray
    val createdMs: Long

    class Item(override val key: ByteArray, override val author: ByteArray, override val createdMs: Long,
               val announcement: Announcement) : News

    class Deleted(override val key: ByteArray, override val author: ByteArray, override val createdMs: Long,
                  val target: ByteArray) : News
}

/** Mirrors the node's checks (otter-server internal/records/validate.go, D65/D66). */
object AnnouncementVerifier {
    const val MAX_TEXT = 4000 // code points per language
    const val MAX_VERSION_NAME = 32
    const val MAX_APK_SIZE = 100L shl 20

    /** Verifies [record] against the news keys of the stored manifest; null if it is not a valid news record. */
    fun verify(record: Record, newsKeys: List<ByteArray>, ctx: SigContext): News? = try {
        val body = RecordBody.parseFrom(record.body)
        val author = body.author.toByteArray()
        val pub = newsKeys.firstOrNull { it.size == 32 && Ids.id16(it).contentEquals(author) }
        when {
            body.id.size() != 16 || author.size != 16 || body.expiresMs != 0L -> null
            pub == null || !RecordCodec.verify(record, pub, ctx) -> null
            body.type == RecordType.ANNOUNCEMENT -> {
                val a = Announcement.parseFrom(body.payload)
                if (!validAnnouncement(a)) null else News.Item(author + body.id.toByteArray(), author, body.createdMs, a)
            }
            body.type == RecordType.ANNOUNCEMENT_DELETED -> {
                val t = AnnouncementDeleted.parseFrom(body.payload).target.toByteArray()
                if (t.size != 32) null else News.Deleted(author + body.id.toByteArray(), author, body.createdMs, t)
            }
            else -> null
        }
    } catch (e: InvalidProtocolBufferException) {
        null
    }

    private fun validText(b: ByteString): Boolean {
        if (!b.isValidUtf8) return false
        val s = b.toStringUtf8()
        return s.codePointCount(0, s.length) <= MAX_TEXT
    }

    private fun validAnnouncement(a: Announcement): Boolean {
        if (!validText(a.textRuBytes) || !validText(a.textEnBytes)) return false
        if (a.textRu.isBlank() && a.textEn.isBlank()) return false
        if (!a.hasUpdate()) return true
        val u: AppUpdate = a.update
        val name = u.versionName
        val n = name.codePointCount(0, name.length)
        // size is uint64: a negative Long is > 2^63, i.e. too big
        return u.versionNameBytes.isValidUtf8 && n in 1..MAX_VERSION_NAME && u.versionCode != 0 &&
            u.sha256.size() == 32 && u.size > 0 && u.size <= MAX_APK_SIZE
    }
}
