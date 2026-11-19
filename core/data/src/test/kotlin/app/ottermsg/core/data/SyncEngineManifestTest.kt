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
}
