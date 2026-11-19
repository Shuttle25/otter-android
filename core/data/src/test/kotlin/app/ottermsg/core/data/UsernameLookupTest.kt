package app.ottermsg.core.data

import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.data.directory.UsernameLookup.Result
import app.ottermsg.core.network.LookupLimitException
import app.ottermsg.core.network.OldNodeException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exact `@username` lookup (M9.5, D137): local store first, then the node; a mismatching answer is «not found». */
@RunWith(RobolectricTestRunner::class)
class UsernameLookupTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val f = DirectoryFixtures(node)

    @After fun tearDown() = g.close()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private suspend fun catalog() = g.db.contactDao().observeCatalog().first().map { it.username }.toSet()

    @Test fun findsVerifiesAndRefusesAMismatch() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        node.putDirectoryEntry(f.member(7, "vera"))
        node.putDirectoryEntry(f.member(8, "zoya"))
        val lookup = g.usernameLookup
        val found = lookup.find(g.session!!, "@Vera ")
        assertTrue(found is Result.Found && found.contact.username == "vera")
        assertFalse("found, not yet a chat partner", "vera" in catalog())
        // hostile node: asked for "ivan", it answers with zoya's (valid, signed) entry
        node.lookupAnswer = { node.withAncestry(setOf(f.uid(8).hex())) }
        assertEquals(Result.NotFound, lookup.find(g.session!!, "ivan"))
        assertFalse("zoya" in catalog())
        // vera is now local: no network call, with or without the clash tag
        node.recordCalls()
        assertTrue(lookup.find(g.session!!, "vera") is Result.Found)
        assertTrue(lookup.find(null, "@vera#" + f.uid(7).hex().take(8)) is Result.Found)
        assertTrue(node.calls.isEmpty())
        // a wrong tag is not vera
        val wrong = f.uid(7).hex().take(8).map { if (it == '0') '1' else '0' }.joinToString("")
        assertEquals(Result.NotFound, lookup.find(g.session!!, "vera#$wrong"))
    }

    @Test fun limitOldNodeOfflineAndInvalid() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        val lookup = g.usernameLookup
        assertEquals(Result.Offline, lookup.find(null, "vera"))
        assertEquals(Result.NotFound, lookup.find(g.session!!, "vera")) // nobody has it
        node.lookupAnswer = { throw LookupLimitException() }
        assertEquals(Result.Limited, lookup.find(g.session!!, "vera"))
        node.lookupAnswer = { throw OldNodeException() }
        assertEquals(Result.OldNode, lookup.find(g.session!!, "vera"))
        node.recordCalls()
        assertEquals(Result.NotFound, lookup.find(g.session!!, "@a b")) // not a username: no call
        assertTrue(node.calls.isEmpty())
        // through the live session
        node.lookupAnswer = null
        node.putDirectoryEntry(f.member(7, "vera"))
        assertTrue(lookup.find("vera") is Result.Found)
        assertTrue(UsernameLookup.VALID.matches("vera#1a2b3c4d"))
    }
}
