package app.ottermsg.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M9.5 (D136, D137): «Написать» shows only people this phone can justify from signed data or its own chats. */
@RunWith(RobolectricTestRunner::class)
class CatalogTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val g = TestGraph(node, clock)
    private val f = DirectoryFixtures(node)
    private val others = mutableListOf<TestGraph>()
    @After fun close() { g.close(); others.forEach { it.close() } }

    private suspend fun catalog() = g.db.contactDao().observeCatalog().first().map { it.username }.toSet()
    private suspend fun TestGraph.login() = node.connect().also { it.login(me.userId, me.accountKey) }

    @Test fun showsOnlyRelatedAndListed() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        node.putDirectoryEntry(f.invitedBy(g.me.accountKey, 2, "kid"))      // my invitee
        node.putDirectoryEntry(f.member(3, "stranger"))                      // unrelated, unlisted: not even served
        node.putDirectoryEntry(f.member(5, "parent"))                        // unlisted inviter of a listed member
        node.putDirectoryEntry(f.invitedBy(5, 4, "open", listed = true))     // listed
        g.directory.sync(g.login())
        assertEquals(setOf("kid", "open"), catalog())
        val contacts = g.db.contactDao()
        assertTrue(contacts.byUsername("kid")!!.related)
        assertTrue(contacts.byUsername("open")!!.listed)
        assertNotNull(contacts.byUsername("parent")) // ancestry: stored for the chain check (D104) ...
        assertFalse(contacts.byUsername("parent")!!.related) // ... never shown
        assertEquals(null, contacts.byUsername("stranger"))
    }

    @Test fun hostileNodeListingAnUnlistedMemberChangesNothing() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        node.directoryAnswer = { it + f.member(5, "hidden") } // the node adds someone who is not listed
        g.directory.sync(g.login())
        assertNotNull(g.db.contactDao().byUsername("hidden")) // stored (chain checks)
        assertEquals(emptySet<String>(), catalog())
    }

    @Test fun inviterIsInTheCatalogFromTheLinkOnAndAfterTheDirectory() = runBlocking {
        val mama = TestGraph(node, clock).also { others += it }
        mama.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        mama.online()
        g.register(mama.invites.create().link, "Я", "me_user").getOrThrow()
        val placeholder = g.db.contactDao().get(mama.me.userId)!!
        assertEquals(0L, placeholder.entryVersion)
        assertTrue(placeholder.related)
        assertEquals(setOf(""), catalog()) // the link placeholder has no username yet
        g.directory.sync(g.login())
        assertEquals(setOf("mama"), catalog())
        // and the inviter sees me: my entry names her as the invite's author
        mama.directory.sync(mama.session!!)
        assertEquals(setOf("me_user"), mama.db.contactDao().observeCatalog().first().map { it.username }.toSet())
    }

    @Test fun syncListsThenRefreshesChatAndGroupPartnersById() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        node.putDirectoryEntry(f.member(3, "chat_partner"))
        node.putDirectoryEntry(f.member(4, "other"))
        g.db.conversationDao().getOrCreateDirect(f.uid(3), "", clock.now())
        val s = g.login()
        node.recordCalls()
        g.directory.sync(s)
        assertEquals(listOf("directory.list", "directory.get"), node.calls.toList())
        assertEquals(setOf("chat_partner"), catalog())
        assertEquals(null, g.db.contactDao().byUsername("other"))
    }

    @Test fun reKeyedChatPartnersNewAccountIsFetched() = runBlocking { // D111: the chat moves once its entry is here
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        node.putDirectoryEntry(f.member(3, "old_phone"))
        node.putDirectoryEntry(f.member(6, "new_phone"))
        g.db.conversationDao().getOrCreateDirect(f.uid(3), "", clock.now())
        node.rekey(f.uid(3), f.uid(6))
        g.directory.sync(g.login())
        assertNotNull(g.db.contactDao().get(f.uid(6)))
        assertNotNull(g.db.conversationDao().byPeer(f.uid(6)))
    }

    @Test fun unknownSenderIsFetchedById() = runBlocking {
        g.register(node.bootstrapLink(), "Я", "me_user").getOrThrow()
        val anna = TestGraph(node, clock).also { others += it }
        anna.register(node.bootstrapLink(), "Анна", "anna").getOrThrow() // unrelated: not in my visible set
        val sa = anna.login()
        anna.directory.fetch(sa, listOf(g.me.userId)) // anna found me (an @username lookup in Task 8)
        anna.messages.sendText(anna.messages.conversationWith(g.me.userId), "привет")
        anna.outbox.drainOnce(sa)
        val mail = node.mailbox(g.me.userId)
        assertEquals(1, mail.size)

        val s = g.login()
        node.recordCalls()
        g.receiver.handle(s, mail)
        assertEquals(listOf("directory.get"), node.calls.toList())
        assertEquals(setOf("anna"), catalog()) // a chat exists now
    }
}
