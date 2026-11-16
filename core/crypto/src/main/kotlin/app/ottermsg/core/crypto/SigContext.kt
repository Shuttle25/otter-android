package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest

/**
 * Record signature context (D109). [networkId] = ID16(network_pub). [v1UntilMs] = the newest manifest's
 * sig_v1_until_ms: 0 → v1 signatures are valid for every record and new records are signed v1; otherwise v1 is valid
 * only for records created before it and new records are signed v2.
 */
class SigContext(val networkId: ByteArray, val v1UntilMs: Long) {
    init { require(networkId.size == 16) { "network id must be 16 bytes" } }

    val signV2: Boolean get() = v1UntilMs != 0L

    companion object {
        fun of(networkPub: ByteArray, manifest: Manifest?) = SigContext(Ids.id16(networkPub), manifest?.sigV1UntilMs ?: 0L)
    }
}
