package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.InvalidProtocolBufferException

class ManifestException(msg: String) : Exception(msg)

object ManifestVerifier {
    /**
     * Verifies a serialized MANIFEST record and returns the manifest if it is valid and newer. [v1UntilMs] is the
     * cut-over of the newest manifest already trusted (0 for a first or an already stored manifest, D109).
     */
    fun verify(recordBytes: ByteArray, networkPub: ByteArray, currentVersion: Long, v1UntilMs: Long): Manifest {
        try {
            val rec = Record.parseFrom(recordBytes)
            if (!RecordCodec.verify(rec, networkPub, SigContext(Ids.id16(networkPub), v1UntilMs))) throw ManifestException("bad signature")
            val body = RecordBody.parseFrom(rec.body)
            if (body.type != RecordType.MANIFEST) throw ManifestException("not a manifest")
            if (!body.author.toByteArray().contentEquals(Ids.id16(networkPub))) throw ManifestException("wrong author")
            val m = Manifest.parseFrom(body.payload)
            if (!m.networkPub.toByteArray().contentEquals(networkPub)) throw ManifestException("foreign network")
            if (m.version <= currentVersion) throw ManifestException("not newer: ${m.version} <= $currentVersion")
            return m
        } catch (e: InvalidProtocolBufferException) {
            throw ManifestException("malformed: ${e.message}")
        }
    }

    /**
     * True when [raw] is a MANIFEST validly self-signed by the network key it names, and that key is not [ours] (D139):
     * a node of another network. A broken signature, garbage or our own manifest is false (noise, not a network change).
     * The signature context is the other network's own (its id; v1 or v2 accepted), as its admin signed it.
     */
    fun foreignNetwork(raw: ByteArray, ours: ByteArray): Boolean = try {
        val rec = Record.parseFrom(raw)
        val body = RecordBody.parseFrom(rec.body)
        val theirs = Manifest.parseFrom(body.payload).networkPub.toByteArray()
        theirs.size == 32 && !theirs.contentEquals(ours) && body.type == RecordType.MANIFEST &&
            body.author.toByteArray().contentEquals(Ids.id16(theirs)) &&
            RecordCodec.verify(rec, theirs, SigContext(Ids.id16(theirs), 0))
    } catch (e: InvalidProtocolBufferException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
