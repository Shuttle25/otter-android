package app.ottermsg.feature.chats

import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.NodeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionBannerTest {
    @Test fun rules() {
        assertNull(bannerFor(ConnectionState.Online(FakeApi, "wss://x", "a"), 0, 10_000))
        assertEquals(R.string.chats_offline, bannerFor(ConnectionState.Offline, 0, 0))
        assertEquals(R.string.chats_offline, bannerFor(ConnectionState.Waiting(5_000, "down"), 0, 0))
        assertNull(bannerFor(ConnectionState.Connecting, 1_000, 2_999)) // short connects stay invisible
        assertEquals(R.string.chats_connecting, bannerFor(ConnectionState.Connecting, 1_000, 3_000))
    }
}

private object FakeApi : NodeApi {
    override val events = kotlinx.coroutines.flow.emptyFlow<app.ottermsg.proto.v1.Event>()
    override suspend fun hello() = TODO()
    override suspend fun register(req: app.ottermsg.proto.v1.RegisterRequest) = TODO()
    override suspend fun login(userId: ByteArray, account: app.ottermsg.core.crypto.Ed25519.KeyPair) = TODO()
    override suspend fun manifest() = TODO()
    override suspend fun directory() = TODO()
    override suspend fun uploadPrekeys(record: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun fetchPrekeys(userId: ByteArray, device: Int) = TODO()
    override suspend fun send(envelope: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun ack(ack: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun createInvite(invite: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun updateDirectory(entry: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun blobCreate(meta: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun blobPut(key: ByteArray, index: Int, data: ByteArray) = TODO()
    override suspend fun blobGet(key: ByteArray, index: Int) = TODO()
    override suspend fun blobAck(record: app.ottermsg.proto.v1.Record) = TODO()
    override suspend fun ping() = TODO()
}
