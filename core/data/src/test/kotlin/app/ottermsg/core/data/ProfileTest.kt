package app.ottermsg.core.data

import app.ottermsg.core.data.account.DirectoryEntries
import app.ottermsg.core.data.account.ProfileError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val graphs = mutableListOf<TestGraph>()
    private val a = TestGraph(node, clock).also { graphs += it }
    private val b = TestGraph(node, clock).also { graphs += it }

    @After fun tearDown() { graphs.forEach { it.close() } }

    /** A ("Мама") joins with the bootstrap invite and invites B ("Боря"); both online and in each other's contacts. */
    private suspend fun twoMembers() {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow()
        b.online()
        eventually(what = "A sees B") { a.db.contactDao().get(b.me.userId) != null }
        eventually(what = "B sees A") { b.db.contactDao().get(a.me.userId) != null }
    }

    /** Puts a newer own entry straight into the node (as if published by another install of the account). */
    private fun bump(g: TestGraph, to: Int, name: String = g.me.displayName) =
        node.putDirectoryEntry(DirectoryEntries.record(g.me, name, to, clock.now()))

    @Test fun offlineEditIsPublishedWhenOnlineAndOthersSeeIt() = runBlocking {
        twoMembers()
        val conv = b.messages.conversationWith(a.me.userId)
        a.offline()
        a.profile.setDisplayName("  Мама Оля ").getOrThrow()
        assertEquals("Мама Оля", a.accounts.account.value!!.displayName)
        assertTrue(a.settings.profilePending())
        assertTrue(a.profile.pending.value)
        assertEquals(0, node.directoryUpdates(a.me.userId))
        a.online()
        eventually(what = "published") { !a.settings.profilePending() }
        assertFalse(a.profile.pending.value)
        assertEquals(2, a.settings.entryVersion())
        eventually(what = "B sees the name") { b.db.contactDao().get(a.me.userId)!!.displayName == "Мама Оля" }
        eventually(what = "B's chat title") { b.db.conversationDao().get(conv)!!.title == "Мама Оля" }
        assertEquals(1, node.directoryUpdates(a.me.userId))
        // the same name again is a no-op
        a.profile.setDisplayName("Мама Оля").getOrThrow()
        assertFalse(a.settings.profilePending())
    }

    @Test fun validation() = runBlocking {
        twoMembers()
        assertEquals(ProfileError.Empty, a.profile.setDisplayName("   ").exceptionOrNull())
        assertEquals(ProfileError.TooLong, a.profile.setDisplayName("я".repeat(65)).exceptionOrNull())
        assertTrue(a.profile.setDisplayName("😀".repeat(64)).isSuccess) // 64 code points, 128 UTF-16 units
        eventually(what = "published") { !a.settings.profilePending() }
        eventually(what = "B sees emoji name") { b.db.contactDao().get(a.me.userId)!!.displayName == "😀".repeat(64) }
    }

    @Test fun conflictContinuesFromServerVersion() = runBlocking {
        twoMembers()
        bump(a, to = 5) // simulates an entry published by an earlier install
        a.profile.setDisplayName("Оля").getOrThrow()
        eventually(what = "published") { !a.settings.profilePending() }
        assertEquals(6, a.settings.entryVersion())
    }

    @Test fun pendingSurvivesRestart() = runBlocking {
        twoMembers()
        a.offline()
        a.profile.setDisplayName("Оля").getOrThrow()
        val a2 = a.restart().also { graphs += it } // new graph on the same database
        assertTrue(a2.settings.profilePending())
        assertEquals("Оля", a2.accounts.load()!!.displayName)
        a2.profile.refresh()
        assertTrue(a2.profile.pending.value)
        a2.online()
        eventually(what = "published after restart") { !a2.settings.profilePending() }
        eventually(what = "B sees it") { b.db.contactDao().get(a.me.userId)!!.displayName == "Оля" }
    }

    @Test fun ownEntryFromDirectoryIsAdoptedWhenNothingPending() = runBlocking {
        twoMembers()
        // a newer own entry (e.g. published by another install of the same account) arrives through the directory
        bump(a, to = 3, name = "Ольга")
        a.directory.sync(a.session!!)
        assertEquals(3, a.settings.entryVersion())
        assertEquals("Ольга", a.accounts.account.value!!.displayName)
        // with a pending local edit the local name wins and is published on top
        a.offline()
        a.profile.setDisplayName("Оля").getOrThrow()
        bump(a, to = 4, name = "Другое")
        a.online()
        eventually(what = "published") { !a.settings.profilePending() }
        assertEquals("Оля", a.accounts.account.value!!.displayName)
        assertEquals(5, a.settings.entryVersion())
    }
}
