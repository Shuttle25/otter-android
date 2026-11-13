package app.ottermsg.core.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpTransport(private val base: OkHttpClient = defaultClient()) : Transport {
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /** Unpinned → the base client (system trust); pinned → one derived client per pin set (shared pool). */
    internal fun clientFor(c: Candidate): OkHttpClient {
        if (c.pins.isEmpty()) return base
        return clients.getOrPut(c.pinsKey) {
            val tm = PinnedTrustManager(c.pins)
            base.newBuilder().sslSocketFactory(tm.sslContext().socketFactory, tm).build()
        }
    }

    override suspend fun connect(candidate: Candidate): Connection = suspendCancellableCoroutine { cont ->
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val counter = AtomicLong()
        val ws = clientFor(candidate).newWebSocket(Request.Builder().url(candidate.url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cont.isActive) cont.resume(OkConnection(webSocket, incoming, counter))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                counter.addAndGet(bytes.size.toLong())
                incoming.trySend(bytes.toByteArray())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); incoming.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
                if (cont.isActive) cont.resumeWithException(t)
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).pingInterval(60, TimeUnit.SECONDS).build()
    }

    private class OkConnection(
        private val ws: WebSocket, override val incoming: Channel<ByteArray>, private val counter: AtomicLong,
    ) : Connection {
        override val bytesIn get() = counter.get()
        override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
        override fun close() { ws.close(1000, null) }
    }
}
