package app.ottermsg.core.network

import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Socket whose bytes are binary WebSocket messages through a no_backups node's `/tunnel` (D114). OkHttp runs its own
 * TLS to the target node over it, so the relay only carries TLS records. [incoming] gets [EOF] when the tunnel ends.
 */
internal class TunnelSocket(private val ws: WebSocket, private val incoming: LinkedBlockingQueue<ByteArray>) : Socket() {
    companion object {
        val EOF = ByteArray(0)
        /** Bytes per tunnel message; the relay refuses messages over 32 KiB. */
        const val CHUNK = 16 * 1024
        /** Back-pressure: OkHttp fails a WebSocket with more than 16 MiB queued. */
        private const val MAX_QUEUED = 1L shl 20
    }

    @Volatile private var timeoutMs = 0
    @Volatile private var closed = false
    private var pending: ByteArray? = null
    private var pos = 0

    override fun connect(endpoint: SocketAddress?, timeout: Int) {} // already connected through the tunnel
    override fun connect(endpoint: SocketAddress?) {}
    override fun isConnected() = true
    override fun isClosed() = closed
    override fun isInputShutdown() = closed
    override fun isOutputShutdown() = closed
    override fun setSoTimeout(timeout: Int) { timeoutMs = timeout }
    override fun getSoTimeout() = timeoutMs
    override fun getInetAddress(): InetAddress = InetAddress.getByAddress("tunnel", ByteArray(4))
    override fun getPort() = 443
    override fun shutdownInput() {}
    override fun shutdownOutput() {}
    override fun close() {
        if (closed) return
        closed = true
        ws.close(1000, null)
        incoming.offer(EOF)
    }

    private val input = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var cur = pending
            if (cur == null || pos >= cur.size) {
                cur = if (timeoutMs > 0) incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("tunnel read timed out")
                else incoming.take()
                if (cur === EOF) { incoming.offer(EOF); return -1 }
                pending = cur; pos = 0
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }
        override fun close() = this@TunnelSocket.close()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            while (o < off + len) {
                if (closed) throw SocketException("tunnel closed")
                while (ws.queueSize() > MAX_QUEUED) { if (closed) throw SocketException("tunnel closed"); Thread.sleep(5) }
                val n = minOf(CHUNK, off + len - o)
                if (!ws.send(b.toByteString(o, n))) throw SocketException("tunnel closed")
                o += n
            }
        }
        override fun close() = this@TunnelSocket.close()
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
}
