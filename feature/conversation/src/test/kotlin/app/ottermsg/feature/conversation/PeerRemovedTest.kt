package app.ottermsg.feature.conversation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId

/** A removed member's chat (D104): notice instead of the composer, no call button, «Участник удалён» chips. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PeerRemovedTest {
    private val anna = ByteArray(16) { 5 }
    private val conv = ConversationEntity(id = 7, peerUserId = anna, title = "Анна", lastActivityMs = 1)
    private fun line(id: Long, kind: Int, data: String? = null) = MessageEntity(localId = id, messageId = byteArrayOf(id.toByte()),
        conversationId = 7, senderId = anna, outgoing = false, sentMs = id, receivedMs = id, body = if (data == null) "привет" else "",
        status = MessageStatus.DELIVERED, kind = kind, systemData = data?.toByteArray())

    @Test fun mapping() {
        val msgs = listOf(line(1, MessageKind.TEXT), line(2, MessageKind.SYSTEM_MEMBER, "revoked"),
            line(3, MessageKind.SYSTEM_MEMBER, "duplicate_invite"))
        val ui = conversationUi(conv, msgs, null, emptyList(), ByteArray(16), ZoneId.of("UTC"), revoked = setOf(anna.hex()))
        assertTrue(ui.peerRemoved)
        assertFalse(ui.canWrite)
        assertFalse(ui.canCall)
        assertEquals(1L, ui.lastIncomingId) // member lines are not messages to mark read
        val notes = ui.items.filterIsInstance<ConversationItem.Bubble>().mapNotNull { it.message.memberNote }
        assertEquals(listOf("revoked", "duplicate_invite"), notes)

        val other = conversationUi(conv, msgs, null, emptyList(), ByteArray(16), ZoneId.of("UTC"), revoked = setOf(ByteArray(16).hex()))
        assertFalse(other.peerRemoved)
        assertTrue(other.canWrite)
        assertTrue(other.canCall)
    }

    private class Store(private val revoked: MutableStateFlow<Set<String>>) : ConversationStore {
        override fun observeMessages(conversationId: Long) = MutableStateFlow<List<MessageEntity>>(emptyList())
        override fun observeConversation(conversationId: Long) =
            MutableStateFlow<ConversationEntity?>(ConversationEntity(id = 7, peerUserId = ByteArray(16) { 5 }, title = "Анна", lastActivityMs = 1))
        override suspend fun sendText(conversationId: Long, text: String): Long? = null
        override suspend fun retry(localId: Long) = true
        override suspend fun deleteLocal(localId: Long) {}
        override suspend fun setDraft(conversationId: Long, draft: String) {}
        override suspend fun markConversationRead(conversationId: Long) {}
        override fun observeRevokedIds() = revoked
    }

    private class Photos : PhotoStore {
        override fun observe(conversationId: Long) = flowOf(emptyMap<Long, AttachmentEntity>())
        override fun observeOne(localId: Long) = flowOf<AttachmentEntity?>(null)
        override suspend fun download(localId: Long) {}
        override suspend fun load(localId: Long, maxPx: Int): Bitmap? = null
        override suspend fun thumb(localId: Long): Bitmap? = null
        override fun galleryNeedsPermission() = false
        override suspend fun saveToGallery(localId: Long) = true
        override suspend fun importPicked(uri: Uri): File? = null
        override fun newCameraFile() = File("camera.jpg")
        override fun discard(file: File) {}
    }

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theChatFollowsTheRevocation() = runTest(dispatcher) {
        val revoked = MutableStateFlow(emptySet<String>())
        val vm = ConversationViewModel(Store(revoked), Photos(), SavedStateHandle(mapOf("id" to 7L)))
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        assertTrue(vm.ui.value.canWrite)
        revoked.value = setOf(anna.hex())
        runCurrent()
        assertTrue(vm.ui.value.peerRemoved)
        assertFalse(vm.ui.value.canWrite)
        assertFalse(vm.ui.value.canCall)
    }
}
