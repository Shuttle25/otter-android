package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.InviteBundle
import com.google.protobuf.InvalidProtocolBufferException
import java.util.Base64

/** `otter://join/<base64url(InviteBundle)>`. Must match Go `admin.EncodeInviteLink`. */
object InviteLink {
    const val PREFIX = "otter://join/"

    fun encode(b: InviteBundle): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b.toByteArray())

    fun decode(link: String): InviteBundle {
        val s = link.trim()
        require(s.startsWith(PREFIX) && s.length > PREFIX.length) { "not an Otter invite link" }
        val raw = try { Base64.getUrlDecoder().decode(s.removePrefix(PREFIX)) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invite link is damaged", e)
        }
        return try { InviteBundle.parseFrom(raw) } catch (e: InvalidProtocolBufferException) {
            throw IllegalArgumentException("invite link is damaged", e)
        }
    }
}
