package app.ottermsg.core.network

import app.ottermsg.proto.v1.PushStatus
import app.ottermsg.proto.v1.PushStatusRequest
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
