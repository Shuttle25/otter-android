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

    @Test fun aPlainNameInAClashIsTheWinnersOnly() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        node.putDirectoryEntry(f.member(9, "vera")) // registered first: the node's plain "vera" (D40)
        node.putDirectoryEntry(f.member(7, "vera")) // the clash loser, `vera#<tag>` on the node
        // the loser is stored first, so the first stored "vera" is the wrong one
        g.directory.fetch(g.session!!, listOf(f.uid(7)))
        g.directory.fetch(g.session!!, listOf(f.uid(9)))
        assertEquals(2, g.db.contactDao().allByUsername("vera").size)
        node.recordCalls()
        val r = g.usernameLookup.find(g.session!!, "vera")
        assertTrue(r is Result.Found && r.contact.userId.contentEquals(f.uid(9)))
        assertEquals(listOf("directory.lookup"), node.calls.toList()) // two stored: the node decides
        // the tag still names the loser locally, with no call
        node.recordCalls()
        val loser = g.usernameLookup.find(null, "vera#" + f.uid(7).hex().take(8))
        assertTrue(loser is Result.Found && loser.contact.userId.contentEquals(f.uid(7)))
        assertTrue(node.calls.isEmpty())
    }

    @Test fun anAncestorWithTheSameNameIsNotTheMatch() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        node.putDirectoryEntry(f.member(5, "vera"))          // an ancestor that happens to share the name
        node.putDirectoryEntry(f.member(7, "vera"))
        node.putDirectoryEntry(f.invitedBy(5, 9, "vera"))   // invited by the first one
        g.directory.fetch(g.session!!, listOf(f.uid(5), f.uid(7)))
        // the answer: the match plus its ancestry, both named "vera"; the match is the one not stored before
        node.lookupAnswer = { node.withAncestry(setOf(f.uid(9).hex())) }
        val r = g.usernameLookup.find(g.session!!, "vera")
        assertTrue(r is Result.Found && r.contact.userId.contentEquals(f.uid(9)))
        // now all three are stored and the answer holds two of them: no way to tell, «not found»
        assertEquals(Result.NotFound, g.usernameLookup.find(g.session!!, "vera"))
    }

    @Test fun ownUsernameIsSelf() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        node.recordCalls()
        assertEquals(Result.Self, g.usernameLookup.find(g.session!!, "@Me_User"))
        assertEquals(Result.Self, g.usernameLookup.find(null, "me_user#" + g.me.userId.hex().take(8)))
        assertTrue(node.calls.isEmpty())
    }
}
