package app.ottermsg.core.data

import app.ottermsg.core.crypto.AuthMessage
import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.Connection
import app.ottermsg.core.network.RpcException
import app.ottermsg.core.network.Transport
import app.ottermsg.proto.v1.BackupAuthRequest
import app.ottermsg.proto.v1.BackupFetchRequest
import app.ottermsg.proto.v1.BlobChunk
import app.ottermsg.proto.v1.BlobGetRequest
import app.ottermsg.proto.v1.BlobPutRequest
import app.ottermsg.proto.v1.Empty
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Frame
import app.ottermsg.proto.v1.Hello
import app.ottermsg.proto.v1.LoginRequest
import app.ottermsg.proto.v1.ManifestResponse
import app.ottermsg.proto.v1.PreKeyFetchRequest
import app.ottermsg.proto.v1.RecordBatch
import app.ottermsg.proto.v1.RecordRequest
import app.ottermsg.proto.v1.RegisterRequest
import app.ottermsg.proto.v1.Request
import app.ottermsg.proto.v1.Response
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.SecureRandom

/**
 * Serves a [FakeNode] over the RPC frame protocol, so a real [app.ottermsg.core.network.ConnectionManager]
 * (SyncEngine.start / acquire) can connect to it. Every candidate reaches the same node.
 */
class FakeNodeTransport(private val node: FakeNode) : Transport {
    @Volatile var connects = 0; private set

    override suspend fun connect(candidate: Candidate): Connection {
        if (!node.reachable) throw IOException("node unreachable")
        connects++
        return Conn(node.connect())
    }

    private inner class Conn(private val s: FakeNode.Session) : Connection {
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val requests = Channel<Request>(Channel.UNLIMITED)
        private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        @Volatile private var closed = false

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                s.events.collect { e -> push(Frame.newBuilder().setEvent(e).build()) }
            }
            scope.launch { for (r in requests) push(Frame.newBuilder().setResponse(handle(r).toBuilder().setId(r.id)).build()) }
            val hello = Hello.newBuilder().setProtocolVersion(1).setNodeId(ByteString.copyFrom(node.nodeId))
                .setNonce(ByteString.copyFrom(nonce)).setManifestVersion(kotlinx.coroutines.runBlocking { s.hello() }.manifestVersion).build()
            push(Frame.newBuilder().setEvent(Event.newBuilder().setType("hello").setBody(hello.toByteString())).build())
        }

        private fun push(f: Frame) { incoming.trySend(f.toByteArray()) }

        override fun send(bytes: ByteArray): Boolean {
            if (closed) return false
            requests.trySend(Frame.parseFrom(bytes).request)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true; s.close(); scope.cancel(); incoming.close(); requests.close()
        }

        private suspend fun handle(r: Request): Response = try {
            ok(dispatch(r.method, r.body))
        } catch (e: RpcException) {
            Response.newBuilder().setStatus(e.status).setError(e.message ?: "").build()
        } catch (e: IOException) {
            close(); Response.newBuilder().setStatus(Status.STATUS_RETRY_LATER).setError("unreachable").build()
        }

        private fun ok(body: MessageLite?): Response = Response.newBuilder().setStatus(Status.STATUS_OK)
            .setBody(body?.toByteString() ?: ByteString.EMPTY).build()

        private fun rec(b: ByteString) = RecordRequest.parseFrom(b).record

        private suspend fun dispatch(method: String, b: ByteString): MessageLite? = when (method) {
            "register" -> { s.register(RegisterRequest.parseFrom(b)); null }
            "auth.login" -> {
                val req = LoginRequest.parseFrom(b)
                val user = req.userId.toByteArray()
                val pub = node.directoryRecords().map { app.ottermsg.proto.v1.DirectoryEntry.parseFrom(
                    app.ottermsg.proto.v1.RecordBody.parseFrom(it.body).payload) }
                    .firstOrNull { it.userId.toByteArray().contentEquals(user) }?.accountPub?.toByteArray()
                    ?: throw RpcException(Status.STATUS_UNAUTHORIZED, "unknown user")
                if (!Ed25519.verify(pub, AuthMessage.build(node.nodeId, nonce, req.tsMs), req.signature.toByteArray()))
                    throw RpcException(Status.STATUS_UNAUTHORIZED, "bad signature")
                s.login(user, Ed25519.KeyPair(ByteArray(32), pub)) // FakeNode compares only the public key
            }
            "manifest.get" -> ManifestResponse.newBuilder().setManifest(s.manifest()).build()
            "directory.sync" -> RecordBatch.newBuilder().addAllRecords(s.directory()).build()
            "prekeys.upload" -> { s.uploadPrekeys(rec(b)); null }
            "prekeys.fetch" -> PreKeyFetchRequest.parseFrom(b).let { s.fetchPrekeys(it.userId.toByteArray(), it.deviceId) }
            "msg.send" -> { s.send(rec(b)); null }
            "msg.ack" -> { s.ack(rec(b)); null }
            "invite.create" -> { s.createInvite(rec(b)); null }
            "directory.update" -> { s.updateDirectory(rec(b)); null }
            "blob.create" -> s.blobCreate(rec(b))
            "blob.put" -> BlobPutRequest.parseFrom(b).let { s.blobPut(it.blobKey.toByteArray(), it.index, it.data.toByteArray()); null }
            "blob.get" -> BlobGetRequest.parseFrom(b).let {
                BlobChunk.newBuilder().setData(ByteString.copyFrom(s.blobGet(it.blobKey.toByteArray(), it.index))).build()
            }
            "blob.ack" -> { s.blobAck(rec(b)); null }
            "ping" -> { s.ping(); Empty.getDefaultInstance() }
            "backup.auth" -> s.backupAuth(BackupAuthRequest.parseFrom(b).username)
            "backup.fetch" -> BackupFetchRequest.parseFrom(b).let { s.backupFetch(it.username, it.authSecret.toByteArray()) }
            "backup.publish" -> { s.backupPublish(rec(b)); null }
            "push.register" -> { s.registerPush(rec(b)); null }
            else -> throw RpcException(Status.STATUS_INVALID, "unknown method $method")
        }
    }
}
