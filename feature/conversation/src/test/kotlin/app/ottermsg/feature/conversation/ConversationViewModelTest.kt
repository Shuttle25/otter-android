package app.ottermsg.feature.conversation

import androidx.lifecycle.SavedStateHandle
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private class FakeStore : ConversationStore {
        val conv = MutableStateFlow<ConversationEntity?>(ConversationEntity(7, ByteArray(16), "Мама", 0, draft = "черновик"))
        val msgs = MutableStateFlow<List<MessageEntity>>(emptyList())
        val sent = mutableListOf<String>(); val drafts = mutableListOf<String>(); var reads = 0
        var gate: CompletableDeferred<Unit>? = null
        override fun observeMessages(conversationId: Long) = msgs
        override fun observeConversation(conversationId: Long) = conv
        override suspend fun sendText(conversationId: Long, text: String): Long? { gate?.await(); sent += text; return sent.size.toLong() }
        override suspend fun retry(localId: Long) = true
        override suspend fun deleteLocal(localId: Long) {}
        override suspend fun setDraft(conversationId: Long, draft: String) { drafts += draft }
        override suspend fun markConversationRead(conversationId: Long) { reads++ }
    }

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore()
    private lateinit var vm: ConversationViewModel

    @Before fun setUp() { Dispatchers.setMain(dispatcher); vm = ConversationViewModel(store, SavedStateHandle(mapOf("id" to 7L))) }
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun restoresDraftAndSavesDebounced() = runTest(dispatcher) {
        runCurrent()
        assertEquals("черновик", vm.text.value)
        vm.onText("п"); vm.onText("пр"); vm.onText("при")
        advanceTimeBy(400); runCurrent()
        assertEquals(emptyList<String>(), store.drafts)
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("при"), store.drafts)
    }

    @Test fun clearsOnlyAfterStoredAndIgnoresDoubleTap() = runTest(dispatcher) {
        runCurrent()
        vm.onText("Привет")
        store.gate = CompletableDeferred()
        vm.send(); vm.send() // double tap
        runCurrent()
        assertEquals("Привет", vm.text.value) // not yet in the local queue
        assertEquals(true, vm.sending.value)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Привет"), store.sent)
        assertEquals("", vm.text.value)
        assertEquals(false, vm.sending.value)
    }

    @Test fun blankIsNotSent() = runTest(dispatcher) {
        runCurrent()
        vm.onText("   ")
        vm.send(); advanceUntilIdle()
        assertEquals(emptyList<String>(), store.sent)
    }

    @Test fun mapsMessagesAndTracksLastIncoming() = runTest(dispatcher) {
        store.msgs.value = listOf(
            MessageEntity(1, ByteArray(16) { 1 }, 7, ByteArray(16), false, 1_000, 1_000, "привет", MessageStatus.DELIVERED),
            MessageEntity(2, ByteArray(16) { 2 }, 7, ByteArray(16), true, 2_000, 2_000, "и тебе", MessageStatus.READ),
        )
        val ui = vm.ui.first { it.items.isNotEmpty() }
        assertEquals("Мама", ui.title)
        assertEquals(1L, ui.lastIncomingId)
        val bubbles = ui.items.filterIsInstance<ConversationItem.Bubble>()
        assertEquals(listOf("привет", "и тебе"), bubbles.map { it.message.text })
        assertEquals("Мама", bubbles[0].message.authorName)
        vm.markRead(); advanceUntilIdle()
        assertEquals(1, store.reads)
    }
}
