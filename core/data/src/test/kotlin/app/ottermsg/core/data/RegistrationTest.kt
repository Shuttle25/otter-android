package app.ottermsg.core.data

import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.RegistrationError
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.signal.SignalKeyValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RegistrationTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val graphs = mutableListOf<TestGraph>()
    private fun graph() = TestGraph(node, clock).also { graphs += it }

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun assertNothingPersisted(g: TestGraph) = runBlocking {
        assertNull(g.db.accountDao().get())
        assertNull(g.db.manifestDao().latest())
        assertTrue(withContext(Dispatchers.IO) { SignalKeyValue.Table.entries.all { g.db.signalKvDao().keys(it.id).isEmpty() } })
        assertNull(g.db.settingsDao().get("registration_id"))
    }

    @Test fun bootstrapRegistrationPersistsEverything() = runBlocking {
        val g = graph()
        g.register(node.bootstrapLink(), " Мама ", "Mama").getOrThrow()
        val acc = g.db.accountDao().get()!!
        assertEquals("mama", acc.username)
        assertEquals("Мама", acc.displayName)
        assertNotNull(g.accounts.account.value)
        assertEquals(1L, g.db.manifestDao().latest()!!.version)
        withContext(Dispatchers.IO) {
            assertTrue(g.db.signalKvDao().keys(SignalKeyValue.Table.SIGNED_PREKEY.id).isNotEmpty())
            assertEquals(50, g.db.signalKvDao().keys(SignalKeyValue.Table.PREKEY.id).size)
        }
        assertEquals(50, node.oneTimeLeft(g.me.userId))
        assertEquals(51, g.settings.nextOneTimeId())
        assertTrue(g.db.contactDao().get(ByteArray(16)) == null) // bootstrap invite has no inviter contact
    }

    @Test fun usernameTakenPersistsNothing() = runBlocking {
        graph().register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        val g = graph()
        val r = g.register(node.bootstrapLink(), "Другая", "mama")
        assertEquals(RegistrationError.UsernameTaken, r.exceptionOrNull())
        assertNothingPersisted(g)
    }

    @Test fun errorMapping() = runBlocking {
        val link = node.bootstrapLink()
        graph().register(link, "Мама", "mama").getOrThrow()
        assertEquals(RegistrationError.InviteUsed, graph().register(link, "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.InviteExpired, graph().register(node.bootstrapLink(ttlMs = -1), "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.BadLink, graph().register("https://example.org", "Боря", "borya").exceptionOrNull())
        assertEquals(RegistrationError.BadUsername, graph().register(node.bootstrapLink(), "Боря", "b!").exceptionOrNull())
        assertEquals(RegistrationError.BadDisplayName, graph().register(node.bootstrapLink(), "   ", "borya").exceptionOrNull())

        // a link whose secret is unknown to the node
        val other = FakeNode(clock::now)
        val unknown = other.bootstrapLink()
        val g = graph()
        g.connector = object : NodeConnector {
            override suspend fun <T> withNode(urls: List<String>, block: suspend (NodeApi) -> T): T = block(node.connect())
        }
        // other's manifest is signed by the same deterministic network key, so the link verifies but the invite is unknown here
        assertEquals(RegistrationError.InviteUnknown, g.register(unknown, "Боря", "borya").exceptionOrNull())

        val offline = graph()
        offline.connector = object : NodeConnector {
            override suspend fun <T> withNode(urls: List<String>, block: suspend (NodeApi) -> T): T = throw IOException("down")
        }
        assertEquals(RegistrationError.Network, offline.register(node.bootstrapLink(), "Боря", "borya").exceptionOrNull())
        assertNothingPersisted(offline)
    }
}
