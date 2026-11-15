package app.ottermsg.feature.conversation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.database.entity.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fake(val saveOk: Boolean, val damaged: Boolean = false) : PhotoStore {
        val loads = mutableListOf<Pair<Long, Int>>(); var saves = 0
        override fun observe(conversationId: Long) = flowOf(emptyMap<Long, AttachmentEntity>())
        override fun observeOne(localId: Long) = flowOf<AttachmentEntity?>(null)
        override suspend fun download(localId: Long) {}
        override suspend fun load(localId: Long, maxPx: Int): Bitmap? {
            loads += localId to maxPx; return if (damaged) null else Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        }
        override suspend fun corrupt(localId: Long) = damaged
        override suspend fun thumb(localId: Long): Bitmap? = null
        override fun galleryNeedsPermission() = false
        override suspend fun saveToGallery(localId: Long): Boolean { saves++; return saveOk }
        override suspend fun importPicked(uri: Uri): File? = null
        override fun newCameraFile() = File("x")
        override fun discard(file: File) {}
    }

    @Test fun loadsAtViewerSizeAndSaves() = runTest(dispatcher) {
        val store = Fake(saveOk = true)
        val vm = PhotoViewerViewModel(store, SavedStateHandle(mapOf("localId" to 5L)))
        advanceUntilIdle()
        assertNotNull(vm.image.value)
        assertFalse(vm.corrupt.value)
        assertEquals(listOf(5L to 4096), store.loads)
        val events = mutableListOf<PhotoEvent>()
        val job = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        vm.save(); advanceUntilIdle()
        assertEquals(listOf(PhotoEvent.Saved), events)
        assertEquals(1, store.saves)
        job.cancel()
    }

    @Test fun damagedPhotoIsCorruptNotLoading() = runTest(dispatcher) {
        val vm = PhotoViewerViewModel(Fake(saveOk = true, damaged = true), SavedStateHandle(mapOf("localId" to 5L)))
        advanceUntilIdle()
        assertNull(vm.image.value)
        assertTrue(vm.corrupt.value)
    }
}
