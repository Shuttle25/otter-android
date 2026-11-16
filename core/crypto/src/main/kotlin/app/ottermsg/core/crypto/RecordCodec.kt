package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.security.SecureRandom

object RecordCodec {
    private val rng = SecureRandom()

    fun body(type: RecordType, author: ByteArray, payload: ByteArray, expiresMs: Long = 0,
             id: ByteArray = ByteArray(16).also(rng::nextBytes), createdMs: Long = System.currentTimeMillis()): RecordBody =
        RecordBody.newBuilder().setId(ByteString.copyFrom(id)).setType(type).setAuthor(ByteString.copyFrom(author))
            .setCreatedMs(createdMs).setExpiresMs(expiresMs).setPayload(ByteString.copyFrom(payload)).build()

    private val V2_PREFIX = "otter-v2\u0000".toByteArray(Charsets.US_ASCII)

    /** "otter-v2\0" ‖ network_id ‖ body: the bytes a v2 signature covers (same as Go records.MessageV2, D109). */
    fun messageV2(networkId: ByteArray, body: ByteArray): ByteArray = V2_PREFIX + networkId + body

    /** Signs the exact serialized body bytes: v2 when [ctx] says so, else v1 (same rule as the Go node). */
    fun sign(body: RecordBody, key: Ed25519.KeyPair, ctx: SigContext): Record {
        val bytes = body.toByteArray()
        val msg = if (ctx.signV2) messageV2(ctx.networkId, bytes) else bytes
        return Record.newBuilder().setBody(ByteString.copyFrom(bytes)).setSignature(ByteString.copyFrom(key.sign(msg))).build()
    }

    /**
     * The one record-signature rule (D109, Go records.VerifySig): a v2 signature for this network is always valid; a
     * v1 signature only when there is no cut-over or the record was created before it.
     */
    fun verify(record: Record, publicKey: ByteArray, ctx: SigContext): Boolean {
        if (record.signature.size() != 64 || publicKey.size != 32) return false
        val bytes = record.body.toByteArray()
        val sig = record.signature.toByteArray()
        if (Ed25519.verify(publicKey, messageV2(ctx.networkId, bytes), sig)) return true
        val created = try { RecordBody.parseFrom(bytes).createdMs } catch (e: InvalidProtocolBufferException) { return false }
        return (ctx.v1UntilMs == 0L || created < ctx.v1UntilMs) && Ed25519.verify(publicKey, bytes, sig)
    }
}
