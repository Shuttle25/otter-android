package app.ottermsg.core.network

import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Frame
import app.ottermsg.proto.v1.Hello
import app.ottermsg.proto.v1.Request
import app.ottermsg.proto.v1.Response
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RpcException(val status: Status, message: String) : Exception("$status: $message")
class RpcTimeoutException(method: String) : IOException("$method timed out")

class RpcClient(private val conn: Connection, scope: CoroutineScope, private val timeoutMs: Long = 15_000) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Response>>()
    // Unlimited channel: the reader never waits for slow event handling, so responses keep flowing while
    // envelopes are being decrypted. Single collector (the session).
    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()
    val hello = CompletableDeferred<Hello>()
    /** Completes when the connection ends (with the cause, or null on clean close). */
    val closed = CompletableDeferred<Throwable?>()

    private val reader = scope.launch {
        var cause: Throwable? = null
        try {
            for (bytes in conn.incoming) {
                val f = Frame.parseFrom(bytes)
                when (f.kindCase) {
                    Frame.KindCase.RESPONSE -> pending.remove(f.response.id)?.complete(f.response)
                    Frame.KindCase.EVENT ->
                        if (f.event.type == "hello") hello.complete(Hello.parseFrom(f.event.body)) else _events.trySend(f.event)
                    else -> {}
                }
            }
        } catch (e: Throwable) {
            cause = e
        } finally {
            val err = IOException("connection closed", cause)
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
            hello.completeExceptionally(err)
            _events.close()
            closed.complete(cause)
        }
    }

    suspend fun call(method: String, body: MessageLite): ByteString {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<Response>()
        pending[id] = d
        if (closed.isCompleted) { // the reader has already failed all pending calls and stopped
            pending.remove(id)
            throw IOException("connection closed")
        }
        val frame = Frame.newBuilder().setRequest(Request.newBuilder().setId(id).setMethod(method).setBody(body.toByteString())).build()
        if (!conn.send(frame.toByteArray())) {
            pending.remove(id)
            throw IOException("send failed")
        }
        val r = try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw RpcTimeoutException(method)
        } finally {
            pending.remove(id)
        }
        if (r.status != Status.STATUS_OK) throw RpcException(r.status, r.error)
        return r.body
    }

    fun close() {
        conn.close()
        reader.cancel()
    }
}
