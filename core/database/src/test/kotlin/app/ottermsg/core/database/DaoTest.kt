package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.ManifestEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: OtterDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test fun latestManifest() = runTest {
        assertNull(db.manifestDao().latest())
        db.manifestDao().insert(ManifestEntity(3, byteArrayOf(3)))
        db.manifestDao().insert(ManifestEntity(5, byteArrayOf(5)))
        assertEquals(5L, db.manifestDao().latest()!!.version)
        assertEquals(5L, db.manifestDao().observeLatest().first()!!.version)
    }

    @Test fun account() = runTest {
        assertNull(db.accountDao().get())
        db.accountDao().insert(AccountEntity(ByteArray(16) { 1 }, "mama", "Мама", byteArrayOf(9), 42))
        val a = db.accountDao().get()!!
        assertEquals("mama", a.username)
        assertArrayEquals(byteArrayOf(9), a.seedEnc)
    }

    @Test fun catalogHasListedRelatedChatAndGroupPartnersOnly() = runTest { // D136
        val c = db.contactDao()
        fun contact(n: Int, name: String, listed: Boolean = false, related: Boolean = false, revoked: Boolean = false) =
            ContactEntity(ByteArray(16) { n.toByte() }, name, name, ByteArray(32), ByteArray(33), verified = false,
                identityChanged = false, revoked = revoked, entryVersion = 1, listed = listed, related = related)
        c.upsert(contact(1, "open", listed = true))
        c.upsert(contact(2, "kid", related = true))
        c.upsert(contact(3, "chat"))
        c.upsert(contact(4, "group"))
        c.upsert(contact(5, "ancestor"))
        c.upsert(contact(6, "gone", listed = true, revoked = true))
        db.conversationDao().insert(ConversationEntity(peerUserId = ByteArray(16) { 3 }, title = "chat", lastActivityMs = 1))
        db.groupDao().insertMembers(listOf(GroupMemberEntity(ByteArray(16) { 9 }, ByteArray(16) { 4 }, admin = false)))
        assertEquals(listOf("chat", "group", "kid", "open"), c.observeCatalog().first().map { it.username })
        assertEquals(6, c.observeAllKnown().first().size)
        assertEquals("kid", c.byUsername("kid")!!.username)
    }
}
