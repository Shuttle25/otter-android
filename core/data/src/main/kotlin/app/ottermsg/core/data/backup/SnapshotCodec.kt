package app.ottermsg.core.data.backup

import androidx.room.withTransaction
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.MessageRecipientEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.BackupAttachment
import app.ottermsg.proto.v1.BackupContact
import app.ottermsg.proto.v1.BackupConversation
import app.ottermsg.proto.v1.BackupGroup
import app.ottermsg.proto.v1.BackupGroupMember
import app.ottermsg.proto.v1.BackupMessage
import app.ottermsg.proto.v1.BackupRecipient
import app.ottermsg.proto.v1.BackupSnapshot
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** DB ↔ BackupSnapshot (D55). Signal state, outbox, seen envelopes, pending group items and endpoint stats are never included. */
object SnapshotCodec {
    const val FORMAT = 1

    suspend fun export(db: OtterDatabase, me: LocalAccount, settings: Settings, nowMs: Long): BackupSnapshot {
        val b = BackupSnapshot.newBuilder().setFormat(FORMAT).setCreatedMs(nowMs).setSeed(bs(me.seed)).setUsername(me.username)
            .setDisplayName(me.displayName).setRegisteredMs(me.createdMs).setEntryVersion(settings.entryVersion())
            .setPhotoAutoDownload(settings.photoAutoDownload()).setPhotoSaveToGallery(settings.photoSaveToGallery())
        db.contactDao().all().forEach { c ->
            b.addContacts(BackupContact.newBuilder().setUserId(bs(c.userId)).setUsername(c.username).setDisplayName(c.displayName)
                .setAccountPub(bs(c.accountPub)).setIdentityPub(bs(c.identityPub)).setVerified(c.verified)
                .setIdentityChanged(c.identityChanged).setRevoked(c.revoked).setEntryVersion(c.entryVersion))
        }
        db.conversationDao().all().forEach { c ->
            val cb = BackupConversation.newBuilder().setId(c.id).setTitle(c.title).setLastActivityMs(c.lastActivityMs)
                .setUnread(c.unread).setDraft(c.draft).setType(c.type)
            c.peerUserId?.let { cb.setPeerUserId(bs(it)) }
            c.groupId?.let { cb.setGroupId(bs(it)) }
            b.addConversations(cb)
        }
        db.messageDao().all().forEach { m ->
            val mb = BackupMessage.newBuilder().setMessageId(bs(m.messageId)).setConversationId(m.conversationId)
                .setSenderId(bs(m.senderId)).setOutgoing(m.outgoing).setSentMs(m.sentMs).setReceivedMs(m.receivedMs)
                .setBody(m.body).setStatus(m.status).setKind(m.kind).setReadReceiptSent(m.readReceiptSent)
            m.systemData?.let { mb.setSystemData(bs(it)) }
            db.attachmentDao().forMessage(m.localId)?.let { a ->
                val ab = BackupAttachment.newBuilder().setBlobKey(bs(a.blobKey)).setKey(bs(a.key)).setDigest(bs(a.digest))
                    .setSize(a.size).setMime(a.mime).setWidth(a.width).setHeight(a.height)
                a.thumb?.let { ab.setThumb(bs(it)) }
                mb.setAttachment(ab)
            }
            db.messageRecipientDao().forMessage(m.localId).forEach { r ->
                mb.addRecipients(BackupRecipient.newBuilder().setUserId(bs(r.userId)).setDeliveredMs(r.deliveredMs ?: 0).setReadMs(r.readMs ?: 0))
            }
            b.addMessages(mb)
        }
        db.groupDao().all().forEach { g ->
            val gb = BackupGroup.newBuilder().setGroupId(bs(g.groupId)).setName(g.name).setFamily(g.family).setVersion(g.version)
                .setStateSigned(bs(g.stateSigned)).setIAmMember(g.iAmMember).setCreatedBy(bs(g.createdBy))
            g.prevState?.let { gb.setPrevState(bs(it)) }
            db.groupDao().members(g.groupId).forEach { gb.addMembers(BackupGroupMember.newBuilder().setUserId(bs(it.userId)).setAdmin(it.admin)) }
            b.addGroups(gb)
        }
        return b.build()
    }

    /** Writes [s] into an empty database (restore). The account row is written by RestoreManager. */
    suspend fun import(s: BackupSnapshot, db: OtterDatabase, settings: Settings) = db.withTransaction {
        s.contactsList.forEach { c ->
            db.contactDao().upsert(ContactEntity(c.userId.toByteArray(), c.username, c.displayName, c.accountPub.toByteArray(),
                c.identityPub.toByteArray(), c.verified, c.identityChanged, c.revoked, c.entryVersion))
        }
        val ids = HashMap<Long, Long>()
        s.conversationsList.forEach { c ->
            ids[c.id] = db.conversationDao().insert(ConversationEntity(peerUserId = c.peerUserId.toByteArray().takeIf { it.isNotEmpty() },
                title = c.title, lastActivityMs = c.lastActivityMs, unread = c.unread, draft = c.draft,
                groupId = c.groupId.toByteArray().takeIf { it.isNotEmpty() }, type = c.type))
        }
        s.messagesList.forEach { m ->
            val cid = ids[m.conversationId]?.takeIf { it > 0 } ?: return@forEach
            // an unsent message can't be sent from the backup (no outbox): it shows as failed, «Повторить» re-sends it
            val status = if (m.outgoing && m.status == MessageStatus.PENDING) MessageStatus.FAILED else m.status
            val local = db.messageDao().insert(MessageEntity(messageId = m.messageId.toByteArray(), conversationId = cid,
                senderId = m.senderId.toByteArray(), outgoing = m.outgoing, sentMs = m.sentMs, receivedMs = m.receivedMs, body = m.body,
                status = status, kind = m.kind, readReceiptSent = m.readReceiptSent,
                systemData = m.systemData.toByteArray().takeIf { it.isNotEmpty() }))
            if (local <= 0) return@forEach
            if (m.hasAttachment()) {
                val a = m.attachment
                db.attachmentDao().insert(AttachmentEntity(messageLocalId = local, blobKey = a.blobKey.toByteArray(), key = a.key.toByteArray(),
                    digest = a.digest.toByteArray(), size = a.size, mime = a.mime, width = a.width, height = a.height,
                    thumb = a.thumb.toByteArray().takeIf { it.isNotEmpty() }, file = null, state = AttachmentState.NOT_IN_BACKUP))
            }
            if (m.recipientsCount > 0) db.messageRecipientDao().insertAll(m.recipientsList.map {
                MessageRecipientEntity(local, it.userId.toByteArray(), it.deliveredMs.takeIf { v -> v > 0 }, it.readMs.takeIf { v -> v > 0 })
            })
        }
        s.groupsList.forEach { g ->
            db.groupDao().upsert(GroupEntity(g.groupId.toByteArray(), g.name, g.family, g.version, g.stateSigned.toByteArray(),
                g.prevState.toByteArray().takeIf { it.isNotEmpty() }, g.iAmMember, g.createdBy.toByteArray()))
            db.groupDao().replaceMembers(g.groupId.toByteArray(), g.membersList.map { GroupMemberEntity(g.groupId.toByteArray(), it.userId.toByteArray(), it.admin) })
        }
        settings.setPhotoAutoDownload(s.photoAutoDownload)
        settings.setPhotoSaveToGallery(s.photoSaveToGallery)
        settings.setEntryVersion(s.entryVersion)
    }

    fun gzip(s: BackupSnapshot): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { s.writeTo(it) } }.toByteArray()
    fun gunzip(input: InputStream): BackupSnapshot = GZIPInputStream(input).use { BackupSnapshot.parseFrom(it) }
}
