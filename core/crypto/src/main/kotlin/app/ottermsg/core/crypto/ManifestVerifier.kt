package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.InvalidProtocolBufferException

class ManifestException(msg: String) : Exception(msg)

object ManifestVerifier {
    /** Verifies a serialized MANIFEST record and returns the manifest if it is valid and newer. */
    fun verify(recordBytes: ByteArray, networkPub: ByteArray, currentVersion: Long): Manifest {
        try {
            val rec = Record.parseFrom(recordBytes)
            if (!RecordCodec.verify(rec, networkPub)) throw ManifestException("bad signature")
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
}
