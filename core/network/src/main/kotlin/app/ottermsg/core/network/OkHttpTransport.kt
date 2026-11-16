package app.ottermsg.core.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.ConnectionPool
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
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

    override suspend fun connect(candidate: Candidate): Connection =
        if (candidate.via != null) connectVia(candidate) else openWebSocket(clientFor(candidate), candidate.url)

    /** [onEnd] runs once the WebSocket has closed or failed (a tunnel closes its relay WebSocket then). */
    private suspend fun openWebSocket(client: OkHttpClient, url: String, onEnd: () -> Unit = {}): Connection = suspendCancellableCoroutine { cont ->
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val counter = AtomicLong()
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cont.isActive) cont.resume(OkConnection(webSocket, incoming, counter))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                counter.addAndGet(bytes.size.toLong())
                incoming.trySend(bytes.toByteArray())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); incoming.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close(); onEnd() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
                onEnd()
                if (cont.isActive) cont.resumeWithException(t)
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    /**
     * D114 (restore only): a WebSocket to the no_backups node's `/tunnel`, then OkHttp's own TLS **pinned to the
     * target's pins** over that stream, then the WebSocket upgrade to the target. The target's name is never looked up
     * and the connection is never pooled; closing the inner connection closes the tunnel.
     */
    private suspend fun connectVia(c: Candidate): Connection {
        val via = checkNotNull(c.via)
        require(c.pins.isNotEmpty() && c.url.startsWith("wss://")) { "a tunnel target must be pinned WSS" }
        val queue = LinkedBlockingQueue<ByteArray>()
        val outer = suspendCancellableCoroutine<WebSocket> { cont ->
            val ws = clientFor(via).newWebSocket(Request.Builder().url(via.url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cont.isActive) cont.resume(webSocket) else webSocket.cancel()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (bytes.size > 0) queue.offer(bytes.toByteArray()) }
                override fun onMessage(webSocket: WebSocket, text: String) { // a relay never speaks itself
                    webSocket.cancel(); queue.offer(TunnelSocket.EOF)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); queue.offer(TunnelSocket.EOF) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { queue.offer(TunnelSocket.EOF) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    queue.offer(TunnelSocket.EOF)
                    if (cont.isActive) cont.resumeWithException(t)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
        val sock = TunnelSocket(outer, queue)
        val used = AtomicBoolean()
        fun once(): Socket = if (used.compareAndSet(false, true)) sock else throw SocketException("tunnel already used")
        val tm = PinnedTrustManager(c.pins)
        val inner = base.newBuilder()
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = once()
                override fun createSocket(host: String?, port: Int): Socket = once()
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = once()
                override fun createSocket(host: InetAddress?, port: Int): Socket = once()
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = once()
            })
            .dns { host -> listOf(InetAddress.getByAddress(host, ByteArray(4))) } // never resolve the target (it may be blocked)
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .sslSocketFactory(tm.sslContext().socketFactory, tm)
            .build()
        return try { openWebSocket(inner, c.url, onEnd = sock::close) } catch (e: Throwable) { sock.close(); throw e }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).pingInterval(60, TimeUnit.SECONDS)
            .addInterceptor(NoCompression).build()
    }

    /**
     * OkHttp offers permessage-deflate on every web socket; a node that accepted it could inflate a few KB into
     * gigabytes before the app sees the message (C17, D93). Nodes never compress, so the offer is removed and an
     * answer that enables it is refused. An application interceptor: OkHttp skips network interceptors for web sockets.
     */
    internal object NoCompression : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val res = chain.proceed(chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build())
            if (res.header("Sec-WebSocket-Extensions") != null) {
                res.close()
                throw java.net.ProtocolException("node enabled WebSocket compression")
            }
            return res
        }
    }

    private class OkConnection(
        private val ws: WebSocket, override val incoming: Channel<ByteArray>, private val counter: AtomicLong,
    ) : Connection {
        override val bytesIn get() = counter.get()
        override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
        override fun close() { ws.close(1000, null) }
    }
}
