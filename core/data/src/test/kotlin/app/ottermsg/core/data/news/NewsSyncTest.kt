package app.ottermsg.core.data.news

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.data.eventually
import app.ottermsg.proto.v1.Announcement
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SyncEngine: news.list after login, on the `news` event, and revocation after a manifest update (D65, D67). */
@RunWith(RobolectricTestRunner::class)
class NewsSyncTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val key = Ed25519.keyPairFromSeed(ByteArray(32) { 6 })

    @After fun tearDown() = a.close()

    private fun item(text: String) = RecordCodec.sign(RecordCodec.body(RecordType.ANNOUNCEMENT, Ids.id16(key.publicKey),
        Announcement.newBuilder().setTextRu(text).build().toByteArray(), createdMs = clock.now()), key)

    private suspend fun bodies() = a.db.messageDao().allNews().map { it.body }

    @Test fun fetchedAtLoginOnEventAndPurgedOnRevocation() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        node.publish(node.signManifest(2, newsKeys = listOf(key.publicKey)), 2)
        node.publishNews(item("Первая"))
        a.online() // login: manifest v2 fetched, then news.list
        eventually(what = "news after login") { bodies() == listOf("Первая") }

        node.publishNews(item("Вторая")) // `news` event
        eventually(what = "news after event") { bodies() == listOf("Первая", "Вторая") }

        node.publish(node.signManifest(3), 3) // key revoked: `manifest` event
        eventually(what = "revoked news purged") { bodies().isEmpty() }
        assertEquals(0, a.db.conversationDao().news()!!.unread)
    }

    @Test fun revokedWhileOfflineIsPurgedAtLogin() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        node.publish(node.signManifest(2, newsKeys = listOf(key.publicKey)), 2)
        node.publishNews(item("Первая"))
        a.online()
        eventually(what = "news after login") { bodies() == listOf("Первая") }
        a.offline()
        node.publish(node.signManifest(3), 3)
        a.online()
        eventually(what = "purged at login") { bodies().isEmpty() }
    }
}
