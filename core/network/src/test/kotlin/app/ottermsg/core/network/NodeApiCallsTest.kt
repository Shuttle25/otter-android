package app.ottermsg.core.network

import app.ottermsg.proto.v1.ApkChunk
import app.ottermsg.proto.v1.BlobChunk
import app.ottermsg.proto.v1.PushStatus
import app.ottermsg.proto.v1.PushStatusRequest
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeApiCallsTest {
    @Test fun turnCredentialsAndPushStatus() = runTest {
        val bodies = mutableMapOf<String, ByteString>()
        val t = FakeTransport { r ->
            bodies[r.method] = r.body
            when (r.method) {
                "turn.credentials" -> ok(TurnCredentials.newBuilder().setUsername("1:ab").setPassword("p").setTtlS(43200)
                    .addUrls("turn:203.0.113.7:3478?transport=udp").build().toByteString())
                "push.status" -> ok(PushStatus.newBuilder().setHasPush(false).build().toByteString())
                else -> err(Status.STATUS_INVALID, "unknown method")
            }
        }
        val api = RemoteNodeApi(RpcClient(t.connect(cand("x")), backgroundScope))
        val creds = api.turnCredentials()
        assertEquals("1:ab", creds.username)
        assertEquals(listOf("turn:203.0.113.7:3478?transport=udp"), creds.urlsList)
        assertFalse(api.pushStatus(ByteArray(16)))
        assertEquals(PushStatusRequest.newBuilder().setUserId(ByteString.copyFrom(ByteArray(16))).build().toByteString(),
            bodies["push.status"])
    }


    private suspend fun apiAnswering(method: String, body: ByteString, scope: kotlinx.coroutines.CoroutineScope):
        Pair<RemoteNodeApi, FakeTransport.FakeConnection> {
        val conn = FakeTransport { r -> if (r.method == method) ok(body) else ok() }.connect(cand("x")) as FakeTransport.FakeConnection
        val rpc = RpcClient(conn, scope).also { it.hello.await() }
        return RemoteNodeApi(rpc) to conn
    }

    @Test fun oversizeBlobChunkDropsTheConnection() = runTest {
        val body = BlobChunk.newBuilder().setData(ByteString.copyFrom(ByteArray(ReceiveLimits.BLOB_CHUNK + 1))).build().toByteString()
        val (api, conn) = apiAnswering("blob.get", body, backgroundScope)
        assertTrue(runCatching { api.blobGet(ByteArray(32), 0) }.exceptionOrNull() is OversizeFrame)
        assertTrue(conn.closed)
    }

    @Test fun oversizeApkChunkDropsTheConnection() = runTest {
        val body = ApkChunk.newBuilder().setData(ByteString.copyFrom(ByteArray(ReceiveLimits.APK_CHUNK + 1))).build().toByteString()
        val (api, conn) = apiAnswering("apk.get", body, backgroundScope)
        assertTrue(runCatching { api.apkGet(ByteArray(32), 0) }.exceptionOrNull() is OversizeFrame)
        assertTrue(conn.closed)
    }

    @Test fun fullSizeChunksPass() = runTest {
        val blob = BlobChunk.newBuilder().setData(ByteString.copyFrom(ByteArray(ReceiveLimits.BLOB_CHUNK))).build().toByteString()
        assertEquals(ReceiveLimits.BLOB_CHUNK, apiAnswering("blob.get", blob, backgroundScope).first.blobGet(ByteArray(32), 0).size)
        val apk = ApkChunk.newBuilder().setData(ByteString.copyFrom(ByteArray(ReceiveLimits.APK_CHUNK))).build().toByteString()
        assertEquals(ReceiveLimits.APK_CHUNK, apiAnswering("apk.get", apk, backgroundScope).first.apkGet(ByteArray(32), 0).data.size())
    }
}
