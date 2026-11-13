package app.ottermsg.feature.conversation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.ottermsg.core.data.calls.CallNotice
import app.ottermsg.core.data.calls.CallStarter
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

/** The chat's call button (D80): push warning, then exactly one dial. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallButtonTest {
    private class Store(conv: ConversationEntity) : ConversationStore {
        val conv = MutableStateFlow<ConversationEntity?>(conv)
        override fun observeMessages(conversationId: Long) = MutableStateFlow<List<MessageEntity>>(emptyList())
        override fun observeConversation(conversationId: Long) = conv
        override suspend fun sendText(conversationId: Long, text: String): Long? = null
        override suspend fun retry(localId: Long) = true
        override suspend fun deleteLocal(localId: Long) {}
        override suspend fun setDraft(conversationId: Long, draft: String) {}
        override suspend fun markConversationRead(conversationId: Long) {}
        override suspend fun sendPhoto(conversationId: Long, source: File, caption: String): Long? = null
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

    private class Calls(var hasPush: Boolean) : CallStarter {
        override val state = MutableStateFlow<CallState>(CallState.Idle)
        override val notices = MutableSharedFlow<CallNotice>()
        val dialed = mutableListOf<Peer>()
        var asked = 0
        override suspend fun peerHasPush(peer: ByteArray): Boolean { asked++; return hasPush }
        override fun dial(peer: Peer) { dialed += peer }
    }

    private val dispatcher = StandardTestDispatcher()
    private val mama = ByteArray(16) { 5 }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(conv: ConversationEntity, calls: Calls) =
        ConversationViewModel(Store(conv), Photos(), SavedStateHandle(mapOf("id" to 7L)), calls = calls)

    @Test fun noPushShowsTheWarningAndConfirmDialsOnce() = runTest(dispatcher) {
        val calls = Calls(hasPush = false)
        val vm = vm(ConversationEntity(7, mama, "Мама", 0), calls)
        var started = 0
        backgroundScope.launch { vm.ui.collect {} }
        backgroundScope.launch { vm.callStarted.collect { started++ } }
        runCurrent()
        assertTrue(vm.ui.value.canCall)
        vm.prepareCall(); runCurrent()
        assertTrue(vm.callWarning.value)
        assertTrue(calls.dialed.isEmpty())
        vm.confirmCall(); vm.confirmCall(); runCurrent()
        assertFalse(vm.callWarning.value)
        assertEquals(1, calls.dialed.size)
        assertEquals(Peer.of(mama, "Мама"), calls.dialed.single())
        assertEquals(1, started)
    }

    @Test fun withPushDialsWithoutWarning() = runTest(dispatcher) {
        val calls = Calls(hasPush = true)
        val vm = vm(ConversationEntity(7, mama, "Мама", 0), calls)
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        vm.prepareCall(); runCurrent()
        assertFalse(vm.callWarning.value)
        assertEquals(1, calls.dialed.size)
    }

    @Test fun cancelDoesNotDial() = runTest(dispatcher) {
        val calls = Calls(hasPush = false)
        val vm = vm(ConversationEntity(7, mama, "Мама", 0), calls)
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        vm.prepareCall(); runCurrent()
        vm.dismissCallWarning(); vm.confirmCall(); runCurrent()
        assertTrue(calls.dialed.isEmpty())
    }

    @Test fun groupAndNewsChatsHaveNoCallButton() = runTest(dispatcher) {
        val calls = Calls(hasPush = true)
        val vm = vm(ConversationEntity(7, null, "Семья", 0, groupId = ByteArray(16) { 1 }), calls)
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        assertFalse(vm.ui.value.canCall)
        vm.prepareCall(); runCurrent()
        assertEquals(0, calls.asked)
        assertTrue(calls.dialed.isEmpty())
    }
}
