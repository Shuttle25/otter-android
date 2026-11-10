package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import java.security.SecureRandom

object RecordCodec {
    private val rng = SecureRandom()

    fun body(type: RecordType, author: ByteArray, payload: ByteArray, expiresMs: Long = 0,
             id: ByteArray = ByteArray(16).also(rng::nextBytes), createdMs: Long = System.currentTimeMillis()): RecordBody =
        RecordBody.newBuilder().setId(ByteString.copyFrom(id)).setType(type).setAuthor(ByteString.copyFrom(author))
            .setCreatedMs(createdMs).setExpiresMs(expiresMs).setPayload(ByteString.copyFrom(payload)).build()

    /** Signs the exact serialized body bytes (same rule as the Go node). */
    fun sign(body: RecordBody, key: Ed25519.KeyPair): Record {
        val bytes = body.toByteArray()
        return Record.newBuilder().setBody(ByteString.copyFrom(bytes)).setSignature(ByteString.copyFrom(key.sign(bytes))).build()
    }

    fun verify(record: Record, publicKey: ByteArray): Boolean =
        record.signature.size() == 64 && Ed25519.verify(publicKey, record.body.toByteArray(), record.signature.toByteArray())
}
