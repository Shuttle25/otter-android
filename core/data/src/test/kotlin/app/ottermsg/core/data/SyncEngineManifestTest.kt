package app.ottermsg.core.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SyncEngineManifestTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val reconnects = AtomicInteger()

    @After fun tearDown() = a.close()

    @Test fun manifestEventStoredAndNodeRemovalReconnects() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.engine.reconnect = { reconnects.incrementAndGet() }
        a.online()
        node.publish(node.signManifest(2, extraHosts = listOf("b")), 2)
        eventually(what = "v2 stored") { a.db.manifestDao().latest()?.version == 2L }
        assertEquals(0, reconnects.get())
        node.publish(node.signManifest(3, withSelf = false, extraHosts = listOf("b")), 3)
        eventually(what = "reconnect after removal") { reconnects.get() == 1 }
        assertEquals(3L, a.db.manifestDao().latest()!!.version)
    }

    @Test fun newerManifestFetchedAtLogin() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.engine.reconnect = { reconnects.incrementAndGet() }
        node.publish(node.signManifest(5, withSelf = false, extraHosts = listOf("b")), 5) // while we were offline
        a.online()
        assertEquals(5L, a.db.manifestDao().latest()!!.version)
        assertEquals(1, reconnects.get()) // the node we logged in to is no longer listed
    }

    @Test fun loginRecordsTheNodeVersionByItsManifestLabel() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        node.serverVersion = ""   // a node before 0.11.0 sends none
        a.online()
        assertEquals(emptyMap<String, String>(), a.settings.nodeVersions())
        node.serverVersion = "0.11.0"
        a.online()
        assertEquals(mapOf("fake" to "0.11.0"), a.settings.nodeVersions())
        node.serverVersion = "0.11.1"
        a.online()
        assertEquals(mapOf("fake" to "0.11.1"), a.settings.nodeVersions())
    }

    @Test fun foreignManifestRaisesNetworkChangedUntilDismissed() = runBlocking {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        assertEquals(false, a.engine.networkChanged.value)
        val k = app.ottermsg.core.crypto.Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
        fun foreign(v: Long): app.ottermsg.proto.v1.Record {
            val m = app.ottermsg.proto.v1.Manifest.newBuilder().setVersion(v).setNetworkPub(bs(k.publicKey)).build()
            val id = app.ottermsg.core.crypto.Ids.id16(k.publicKey)
            return app.ottermsg.core.crypto.RecordCodec.sign(app.ottermsg.core.crypto.RecordCodec.body(
                app.ottermsg.proto.v1.RecordType.MANIFEST, id, m.toByteArray(), createdMs = v), k, app.ottermsg.core.crypto.SigContext(id, 0))
        }
        node.publish(foreign(2), 2)
        eventually(what = "network changed") { a.engine.networkChanged.value }
        assertEquals(1L, a.db.manifestDao().latest()!!.version) // nothing stored
        a.engine.dismissNetworkChanged()
        assertEquals(false, a.engine.networkChanged.value)
        node.publish(foreign(3), 3)                               // dismissed until the app restarts
        node.publish(node.signManifest(4), 4)
        eventually(what = "own v4 stored after it") { a.db.manifestDao().latest()?.version == 4L }
        assertEquals(false, a.engine.networkChanged.value)
    }
}
