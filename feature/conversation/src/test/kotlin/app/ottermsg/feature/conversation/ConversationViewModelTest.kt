package app.ottermsg.feature.conversation

import androidx.lifecycle.SavedStateHandle
import android.graphics.Bitmap
import android.net.Uri
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.database.entity.AttachmentEntity
import kotlinx.coroutines.flow.flowOf
import java.io.File
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
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
        val photos = mutableListOf<String>(); var photoGate: CompletableDeferred<Unit>? = null; var photoResult: Long? = 1
        override suspend fun sendPhoto(conversationId: Long, source: File, caption: String): Long? {
            photoGate?.await(); photos += "${source.name}:$caption"; return photoResult
        }
    }

    private class FakePhotos : PhotoStore {
        val discarded = mutableListOf<String>()
        override fun observe(conversationId: Long) = flowOf(emptyMap<Long, AttachmentEntity>())
        override fun observeOne(localId: Long) = flowOf<AttachmentEntity?>(null)
        override suspend fun download(localId: Long) {}
        override suspend fun load(localId: Long, maxPx: Int): Bitmap? = null
        override suspend fun thumb(localId: Long): Bitmap? = null
        override fun galleryNeedsPermission() = false
        override suspend fun saveToGallery(localId: Long) = true
        override suspend fun importPicked(uri: Uri): File? = File("picked.jpg")
        override fun newCameraFile() = File("camera.jpg")
        override fun discard(file: File) { discarded += file.name }
    }
    private val photos = FakePhotos()

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore()
    private lateinit var vm: ConversationViewModel

    @Before fun setUp() { Dispatchers.setMain(dispatcher); vm = ConversationViewModel(store, photos, SavedStateHandle(mapOf("id" to 7L))) }

    @Test fun photoIsSentOnceForTwoTapsAndClearsTheDraft() = runTest(dispatcher) {
        runCurrent()
        vm.onPicked(Uri.parse("content://x/1")); runCurrent()
        assertEquals("picked.jpg", vm.pendingPhoto.value)
        store.photoGate = CompletableDeferred()
        vm.sendPhoto("подпись"); vm.sendPhoto("подпись")
        runCurrent()
        assertEquals(true, vm.photoSending.value)
        store.photoGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("picked.jpg:подпись"), store.photos)
        assertEquals(null, vm.pendingPhoto.value)
        assertEquals("", vm.text.value)
    }

    @Test fun visibleChatIsTheActiveOne() {
        val active = app.ottermsg.core.data.push.ActiveConversation()
        val v = ConversationViewModel(store, photos, SavedStateHandle(mapOf("id" to 7L)), active)
        v.onVisible(true)
        assertEquals(7L, active.id)
        v.onVisible(false)
        assertEquals(null, active.id)
        active.id = 9L // another chat became active first: leaving this one keeps it
        v.onVisible(false)
        assertEquals(9L, active.id)
    }

    @Test fun unreadablePhotoKeepsThePreview() = runTest(dispatcher) {
        runCurrent()
        vm.onPicked(Uri.parse("content://x/1")); runCurrent()
        store.photoResult = null
        vm.sendPhoto(""); advanceUntilIdle()
        assertEquals("picked.jpg", vm.pendingPhoto.value)
    }

    @Test fun cancelKeepsTheDraftAndDiscardsTheCopy() = runTest(dispatcher) {
        runCurrent()
        vm.onPicked(Uri.parse("content://x/1")); runCurrent()
        vm.cancelPhoto()
        assertEquals(null, vm.pendingPhoto.value)
        assertEquals("черновик", vm.text.value)
        assertEquals(listOf("picked.jpg"), photos.discarded)
    }

    @Test fun cameraCancelledDiscardsTheFile() = runTest(dispatcher) {
        runCurrent()
        vm.newCameraFile()
        vm.onCameraResult(false)
        assertEquals(null, vm.pendingPhoto.value)
        assertEquals(listOf("camera.jpg"), photos.discarded)
    }
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

    private class FakeDownloader : app.ottermsg.core.data.news.UpdateDownloader {
        var result: () -> app.ottermsg.core.data.news.UpdateResult = { app.ottermsg.core.data.news.UpdateResult.Offline }
        var gate: CompletableDeferred<Unit>? = null; var calls = 0
        override suspend fun download(u: app.ottermsg.proto.v1.AppUpdate, progress: (Float) -> Unit): app.ottermsg.core.data.news.UpdateResult {
            calls++; progress(0.42f); gate?.await(); return result()
        }
    }

    @Test fun updateDownloadsOnceThenInstalls() = runTest(dispatcher) {
        val d = FakeDownloader(); val installed = mutableListOf<File>()
        val v = ConversationViewModel(store, photos, SavedStateHandle(mapOf("id" to 7L)), downloader = d,
            installedVersion = app.ottermsg.core.data.news.InstalledVersion { 5 },
            installer = app.ottermsg.core.data.news.UpdateInstaller { installed += it })
        assertEquals(5, v.installedCode)
        val u = app.ottermsg.proto.v1.AppUpdate.newBuilder().setVersionName("0.6.0").setVersionCode(6).build()
        d.gate = CompletableDeferred(); d.result = { app.ottermsg.core.data.news.UpdateResult.Ready(File("x.apk")) }
        v.startUpdate(u); v.startUpdate(u); runCurrent()
        assertEquals(UpdateUi.Downloading(42), v.updateState.value)
        d.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, d.calls)
        assertEquals(listOf(File("x.apk")), installed)
        assertEquals(UpdateUi.Idle, v.updateState.value)
    }

    @Test fun updateErrorsMapToMessages() = runTest(dispatcher) {
        val d = FakeDownloader()
        val v = ConversationViewModel(store, photos, SavedStateHandle(mapOf("id" to 7L)), downloader = d)
        val u = app.ottermsg.proto.v1.AppUpdate.getDefaultInstance()
        for ((r, msg) in listOf<Pair<() -> app.ottermsg.core.data.news.UpdateResult, Int>>(
            { app.ottermsg.core.data.news.UpdateResult.Corrupt } to R.string.news_update_corrupt,
            { app.ottermsg.core.data.news.UpdateResult.NotOnServer } to R.string.news_update_not_found,
            { app.ottermsg.core.data.news.UpdateResult.Offline } to R.string.news_update_offline,
            { throw java.io.IOException("x") } to R.string.news_update_offline,
            { throw app.ottermsg.core.network.RpcTimeoutException("apk.get") } to R.string.news_update_offline,
        )) {
            d.result = r
            v.startUpdate(u); advanceUntilIdle()
            assertEquals(UpdateUi.Error(msg), v.updateState.value)
        }
    }
}
