package app.ottermsg.core.data

import app.ottermsg.core.data.account.DirectoryEntries
import app.ottermsg.proto.v1.DirectoryEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** «Показывать меня в каталоге сети» (M9.5, D136): the signed `listed` flag of our own entry, off by default. */
@RunWith(RobolectricTestRunner::class)
class ListedEntryTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)

    @After fun tearDown() = g.close()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun mine(): DirectoryEntry = node.entries().filter { it.first == g.me.userId.hex() }.maxBy { it.second.version }.second

    @Test fun offByDefaultSwitchingOnPublishesListedEntryAndLaterEntriesKeepIt() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        assertFalse(g.settings.catalogListed())
        assertFalse(mine().listed)
        g.online()
        g.profile.setListed(true)
        assertTrue(g.settings.catalogListed())
        eventually(what = "published") { !g.settings.profilePending() }
        assertTrue(mine().listed)
        assertEquals(2L, mine().version)
        // a name change keeps the flag
        g.profile.setDisplayName("Я снова").getOrThrow()
        eventually(what = "name published") { !g.settings.profilePending() }
        assertEquals(3L, mine().version)
        assertTrue(mine().listed)
        assertEquals("Я снова", mine().displayName)
        // switching off publishes again
        g.profile.setListed(false)
        eventually(what = "off published") { !g.settings.profilePending() }
        assertEquals(4L, mine().version)
        assertFalse(mine().listed)
    }

    @Test fun offlineSwitchIsRetriedAfterAConflict() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        g.offline()
        // an entry published by an earlier install of the account
        node.putDirectoryEntry(DirectoryEntries.record(g.me, "Я", 5, clock.now()))
        g.profile.setListed(true)
        assertTrue(g.settings.profilePending())
        g.online()
        eventually(what = "published") { !g.settings.profilePending() }
        assertEquals(6L, mine().version)
        assertTrue(mine().listed)
    }

    /** A restore (or another install of the account) learns the flag from its own signed entry. */
    @Test fun ownNewerEntryCarriesTheFlagToThisPhone() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        g.online()
        g.offline()
        node.putDirectoryEntry(DirectoryEntries.record(g.me, "Я", 5, clock.now(), listed = true))
        g.online()
        eventually(what = "flag adopted") { g.settings.catalogListed() }
        assertFalse(g.settings.profilePending())
    }
}
