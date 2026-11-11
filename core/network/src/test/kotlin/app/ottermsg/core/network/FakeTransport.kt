package app.ottermsg.core.network

import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Frame
import app.ottermsg.proto.v1.Hello
import app.ottermsg.proto.v1.Request
import app.ottermsg.proto.v1.Response
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import kotlinx.coroutines.channels.Channel
import java.io.IOException

/** In-memory "node": answers requests with [handler], can push events and drop the connection. */
class FakeTransport(
    var failUrls: Set<String> = emptySet(),
    /** Connect latency per url (virtual time). */
    var delays: Map<String, Long> = emptyMap(),
    /** These urls connect but never send hello. */
    var silentUrls: Set<String> = emptySet(),
    val handler: (Request) -> Response,
) : Transport {
    val connections = mutableListOf<FakeConnection>()
    val byUrl = mutableMapOf<String, MutableList<FakeConnection>>()
    override suspend fun connect(candidate: Candidate): Connection {
        val url = candidate.url
        delays[url]?.let { kotlinx.coroutines.delay(it) }
        if (url in failUrls) throw IOException("refused $url")
        return FakeConnection(handler).also {
            synchronized(this) { connections += it; byUrl.getOrPut(url) { mutableListOf() } += it }
            if (url !in silentUrls) it.push(helloEvent())
        }
    }
    private fun helloEvent() = Frame.newBuilder().setEvent(Event.newBuilder().setType("hello")
        .setBody(Hello.newBuilder().setProtocolVersion(1).setNodeId(ByteString.copyFrom(ByteArray(16) { 1 }))
            .setNonce(ByteString.copyFrom(ByteArray(32) { 2 })).build().toByteString())).build()

    class FakeConnection(private val handler: (Request) -> Response) : Connection {
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var closed = false
        fun push(f: Frame) { incoming.trySend(f.toByteArray()) }
        fun drop() { incoming.close(IOException("dropped")) }
        override fun send(bytes: ByteArray): Boolean {
            if (closed) return false
            val req = Frame.parseFrom(bytes).request
            push(Frame.newBuilder().setResponse(handler(req).toBuilder().setId(req.id)).build())
            return true
        }
        override fun close() { closed = true; incoming.close() }
    }
}

fun ok(body: ByteString = ByteString.EMPTY): Response = Response.newBuilder().setStatus(Status.STATUS_OK).setBody(body).build()
fun err(s: Status, msg: String): Response = Response.newBuilder().setStatus(s).setError(msg).build()

/** Candidate for tests: url doubles as label. */
fun cand(url: String, priority: Int = 0) = Candidate(url.toByteArray(), url, url, url, emptyList(), priority)
