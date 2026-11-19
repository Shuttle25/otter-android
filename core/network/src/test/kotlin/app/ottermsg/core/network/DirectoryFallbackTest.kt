package app.ottermsg.core.network

import app.ottermsg.proto.v1.DirectoryGetRequest
import app.ottermsg.proto.v1.DirectoryLookupRequest
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBatch
import app.ottermsg.proto.v1.Request
import app.ottermsg.proto.v1.Response
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** M9.5 (D135/D137): directory.list/get/lookup and the fallback on a node from before 0.11.0. */
class DirectoryFallbackTest {
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sample = Record.newBuilder().setBody(ByteString.copyFromUtf8("entry")).build()
    private fun batch(vararg r: Record) = ok(RecordBatch.newBuilder().addAllRecords(r.toList()).build().toByteString())

    private suspend fun remoteWith(scope: CoroutineScope, answers: Map<String, (Request) -> Response>): RemoteNodeApi {
        val t = FakeTransport { r -> calls += r.method; answers[r.method]?.invoke(r) ?: err(Status.STATUS_INVALID, "unknown method \"${r.method}\"") }
        val rpc = RpcClient(t.connect(cand("x")), scope).also { it.hello.await() }
        return RemoteNodeApi(rpc)
    }

    @Test fun listFallsBackToSyncOnAnOldNode() = runTest {
        val api = remoteWith(backgroundScope, mapOf("directory.sync" to { _ -> batch(sample) }))
        assertEquals(listOf(sample), api.directoryList())
        assertEquals(listOf(sample), api.directoryList()) // second call goes straight to sync
        assertEquals(1, calls.count { it == "directory.list" })
        assertEquals(listOf(sample), api.directoryGet(listOf(ByteArray(16)))) // remembered: get uses sync too
        assertEquals(0, calls.count { it == "directory.get" })
    }

    @Test fun getFallsBackToSyncOnAnOldNode() = runTest {
        val api = remoteWith(backgroundScope, mapOf("directory.sync" to { _ -> batch(sample) }))
        assertEquals(listOf(sample), api.directoryGet(listOf(ByteArray(16))))
        assertEquals(listOf(sample), api.directoryList())
        assertEquals(0, calls.count { it == "directory.list" })
    }

    @Test fun newNodeAnswersListAndGetInChunksOf256() = runTest {
        val sizes = mutableListOf<Int>()
        val api = remoteWith(backgroundScope, mapOf(
            "directory.list" to { _ -> batch(sample) },
            "directory.get" to { r -> sizes += DirectoryGetRequest.parseFrom(r.body).userIdsCount; batch(sample) },
        ))
        assertEquals(listOf(sample), api.directoryList())
        assertEquals(listOf(sample, sample), api.directoryGet(List(300) { ByteArray(16) { b -> (it + b).toByte() } }))
        assertEquals(listOf(256, 44), sizes)
        assertTrue("directory.sync" !in calls)
    }

    @Test fun lookupMapsStatuses() = runTest {
        var asked = ""
        val found = remoteWith(backgroundScope, mapOf("directory.lookup" to { r -> asked = DirectoryLookupRequest.parseFrom(r.body).username; batch(sample) }))
        assertEquals(listOf(sample), found.directoryLookup("anna"))
        assertEquals("anna", asked)
        val api = remoteWith(backgroundScope, mapOf("directory.lookup" to { _ -> err(Status.STATUS_NOT_FOUND, "user") }))
        assertNull(api.directoryLookup("nobody"))
        val limited = remoteWith(backgroundScope, mapOf("directory.lookup" to { _ -> err(Status.STATUS_QUOTA, "limit") }))
        assertTrue(runCatching { limited.directoryLookup("x") }.exceptionOrNull() is LookupLimitException)
        val old = remoteWith(backgroundScope, emptyMap())
        assertTrue(runCatching { old.directoryLookup("x") }.exceptionOrNull() is OldNodeException)
        val other = remoteWith(backgroundScope, mapOf("directory.lookup" to { _ -> err(Status.STATUS_INVALID, "bad username") }))
        assertTrue(runCatching { other.directoryLookup("x") }.exceptionOrNull() is RpcException)
    }
}
