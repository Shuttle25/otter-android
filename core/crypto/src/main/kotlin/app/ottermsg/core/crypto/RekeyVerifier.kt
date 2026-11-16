package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Rekey
import com.google.protobuf.InvalidProtocolBufferException

/**
 * A network-signed REKEY (D111), checked like the node does (otter-server internal/records/validate.go): type REKEY,
 * author = ID16(network_pub), permanent, two distinct 16-byte accounts, signature by the network key under [ctx].
 * A REKEY signed by a node, a member (the old account itself) or another network is null.
 */
object RekeyVerifier {
    fun verify(record: Record, networkPub: ByteArray, ctx: SigContext): Rekey? = try {
        val body = RecordBody.parseFrom(record.body)
        if (body.type != RecordType.REKEY) null else {
            val k = Rekey.parseFrom(body.payload)
            when {
                body.expiresMs != 0L -> null
                !body.author.toByteArray().contentEquals(Ids.id16(networkPub)) -> null
                k.oldUserId.size() != 16 || k.newUserId.size() != 16 || k.oldUserId == k.newUserId -> null
                !RecordCodec.verify(record, networkPub, ctx) -> null
                else -> k
            }
        }
    } catch (e: InvalidProtocolBufferException) { null }
}
