package app.ottermsg.core.network

import app.ottermsg.core.crypto.AuthMessage
import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.proto.v1.Empty
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.LoginRequest
import app.ottermsg.proto.v1.ManifestResponse
import app.ottermsg.proto.v1.PreKeyBundle
import app.ottermsg.proto.v1.PreKeyFetchRequest
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBatch
import app.ottermsg.proto.v1.RecordRequest
import app.ottermsg.proto.v1.RegisterRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow

/** The node's client API (docs/02 §4). Faked in core:data tests. */
interface NodeApi {
    val events: Flow<Event>
    /** node_id from this connection's hello (owner of one-time prekeys we upload here). */
    suspend fun nodeId(): ByteArray
    suspend fun register(req: RegisterRequest)
    suspend fun login(userId: ByteArray, account: Ed25519.KeyPair)
    suspend fun manifest(): Record
    suspend fun directory(): List<Record>
    suspend fun uploadPrekeys(record: Record)
    suspend fun fetchPrekeys(userId: ByteArray, device: Int = 1): PreKeyBundle
    suspend fun send(envelope: Record)
    suspend fun ack(ack: Record)
    suspend fun createInvite(invite: Record)
    suspend fun ping()
}

class RemoteNodeApi(private val rpc: RpcClient, private val clock: () -> Long = System::currentTimeMillis) : NodeApi {
    override val events: Flow<Event> = rpc.events
    override suspend fun nodeId(): ByteArray = rpc.hello.await().nodeId.toByteArray()
    private fun req(r: Record) = RecordRequest.newBuilder().setRecord(r).build()

    override suspend fun register(req: RegisterRequest) { rpc.call("register", req) }

    override suspend fun login(userId: ByteArray, account: Ed25519.KeyPair) {
        val h = rpc.hello.await()
        val ts = clock()
        val sig = account.sign(AuthMessage.build(h.nodeId.toByteArray(), h.nonce.toByteArray(), ts))
        rpc.call("auth.login", LoginRequest.newBuilder().setUserId(ByteString.copyFrom(userId)).setDeviceId(1)
            .setTsMs(ts).setSignature(ByteString.copyFrom(sig)).build())
    }

    override suspend fun manifest(): Record = ManifestResponse.parseFrom(rpc.call("manifest.get", Empty.getDefaultInstance())).manifest
    override suspend fun directory(): List<Record> = RecordBatch.parseFrom(rpc.call("directory.sync", Empty.getDefaultInstance())).recordsList
    override suspend fun uploadPrekeys(record: Record) { rpc.call("prekeys.upload", req(record)) }
    override suspend fun fetchPrekeys(userId: ByteArray, device: Int): PreKeyBundle =
        PreKeyBundle.parseFrom(rpc.call("prekeys.fetch", PreKeyFetchRequest.newBuilder().setUserId(ByteString.copyFrom(userId)).setDeviceId(device).build()))
    override suspend fun send(envelope: Record) { rpc.call("msg.send", req(envelope)) }
    override suspend fun ack(ack: Record) { rpc.call("msg.ack", req(ack)) }
    override suspend fun createInvite(invite: Record) { rpc.call("invite.create", req(invite)) }
    override suspend fun ping() { rpc.call("ping", Empty.getDefaultInstance()) }
}
