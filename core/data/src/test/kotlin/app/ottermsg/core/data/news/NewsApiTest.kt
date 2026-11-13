package app.ottermsg.core.data.news

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.FakeNodeTransport
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.RemoteNodeApi
import app.ottermsg.core.network.RpcClient
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Announcement
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.security.SecureRandom

/** news.list and apk.get through RemoteNodeApi over the real frame protocol. */
@RunWith(RobolectricTestRunner::class)
class NewsApiTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.cancel(); g.close() }

    private suspend fun api(): RemoteNodeApi {
        val c = g.manifests.candidates(true).first()
        val api = RemoteNodeApi(RpcClient(FakeNodeTransport(node).connect(c), scope), clock::now)
        api.login(g.me.userId, g.me.accountKey)
        return api
    }

    @Test fun newsListAndApkGet() = runBlocking {
        g.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val key = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
        val rec = RecordCodec.sign(RecordCodec.body(RecordType.ANNOUNCEMENT, Ids.id16(key.publicKey),
            Announcement.newBuilder().setTextRu("Привет").build().toByteArray()), key)
        node.publishNews(rec)
        val apk = ByteArray(FakeNode.APK_CHUNK + 10).also(SecureRandom()::nextBytes)
        node.importApk(apk)
        val api = api()
        assertEquals(listOf(rec), api.newsList())
        val sha = MessageDigest.getInstance("SHA-256").digest(apk)
        val c0 = api.apkGet(sha, 0)
        val c1 = api.apkGet(sha, 1)
        assertEquals(apk.size.toLong(), c0.totalSize)
        assertArrayEquals(apk, c0.data.toByteArray() + c1.data.toByteArray())
        val e = assertThrows(RpcException::class.java) { runBlocking { api.apkGet(sha, 2) } }
        assertEquals(Status.STATUS_NOT_FOUND, e.status)
    }
}
