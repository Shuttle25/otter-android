package app.ottermsg.core.data.media

import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.bs
import app.ottermsg.proto.v1.Attachment
import app.ottermsg.proto.v1.BlobMeta
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.proto.v1.RecordType
import java.io.File

/** Builds the signed BLOB_META of an encrypted file (D44) and the Attachment that points at it (D46). */
object BlobMetaBuilder {
    const val TTL_MS = 30L * 24 * 3600 * 1000

    fun build(id: ByteArray, me: LocalAccount, file: File, readers: List<ByteArray>, now: Long): Record {
        val meta = BlobMeta.newBuilder().setSize(file.length()).setChunkSize(BlobCipher.CHUNK)
            .addAllChunkSha256(BlobCipher.chunkHashes(file).map(::bs)).addAllReaders(readers.map(::bs)).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.BLOB_META, me.userId, meta.toByteArray(),
            expiresMs = now + TTL_MS, id = id, createdMs = now), me.accountKey)
    }

    /** Re-signs [old] with the same id (so the blob key, the ciphertext's aad, stays) and a fresh time window. */
    fun resign(old: Record, me: LocalAccount, now: Long): Record {
        val body = RecordBody.parseFrom(old.body)
        return RecordCodec.sign(RecordCodec.body(RecordType.BLOB_META, me.userId, body.payload.toByteArray(),
            expiresMs = now + TTL_MS, id = body.id.toByteArray(), createdMs = now), me.accountKey)
    }

    fun blobKey(author: ByteArray, id: ByteArray) = author + id

    fun attachment(a: AttachmentEntity): Attachment = Attachment.newBuilder().setBlobKey(bs(a.blobKey)).setKey(bs(a.key))
        .setDigest(bs(a.digest)).setSize(a.size).setMime(a.mime).setWidth(a.width).setHeight(a.height)
        .apply { a.thumb?.let { setThumbnail(bs(it)) } }.build()
}
