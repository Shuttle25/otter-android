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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build(),
) : Transport {
    override suspend fun connect(url: String): Connection = suspendCancellableCoroutine { cont ->
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cont.isActive) cont.resume(OkConnection(webSocket, incoming))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { incoming.trySend(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); incoming.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
                if (cont.isActive) cont.resumeWithException(t)
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private class OkConnection(private val ws: WebSocket, override val incoming: Channel<ByteArray>) : Connection {
        override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())
        override fun close() { ws.close(1000, null) }
    }
}
