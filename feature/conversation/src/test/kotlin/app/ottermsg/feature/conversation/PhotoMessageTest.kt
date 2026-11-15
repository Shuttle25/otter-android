package app.ottermsg.feature.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class PhotoMessageTest {
    @get:Rule val compose = createComposeRule()

    private fun msg(state: PhotoState, caption: String = "", outgoing: Boolean = false) =
        MessageUi(1, outgoing, caption, 0, if (outgoing) DeliveryStatus.SENT else DeliveryStatus.DELIVERED, false, "Мама",
            photo = PhotoUi(800, 600, state, if (state == PhotoState.Downloading) .5f else null))

    private fun description(state: PhotoState, caption: String = ""): String {
        compose.setContent { OtterTheme { MessageBubble(msg(state, caption), firstInGroup = true) } }
        return compose.onNode(SemanticsMatcher("bubble") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
    }

    @Test fun readyReadsAsPhotoWithCaption() {
        val d = description(PhotoState.Ready, "Дача")
        assertTrue(d, d.contains("Фото") && d.contains("Дача"))
    }

    @Test fun remoteOffersDownload() {
        var downloads = 0
        compose.setContent { OtterTheme { MessageBubble(msg(PhotoState.Remote), firstInGroup = true, onPhotoDownload = { downloads++ }) } }
        compose.onNodeWithText("Загрузить фото", useUnmergedTree = true).assertExists().performClick()
        assertEquals(1, downloads)
    }

    @Test fun failedAndGoneLabels() {
        assertTrue(description(PhotoState.Gone).contains("Фото недоступно"))
    }

    @Test fun failedShowsRetry() {
        compose.setContent { OtterTheme { MessageBubble(msg(PhotoState.Failed), firstInGroup = true) } }
        compose.onNodeWithText("Не удалось загрузить", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Повторить", useUnmergedTree = true).assertExists()
    }

    @Test fun downloadingReadsAsLoading() {
        assertTrue(description(PhotoState.Downloading).contains("Фото загружается"))
    }

    @Test fun photoUiMapsStatesAndProgress() {
        fun a(state: Int, done: Int = 0) = AttachmentEntity(messageLocalId = 1, blobKey = ByteArray(32), key = ByteArray(32), digest = ByteArray(32),
            size = 4L * 262144, mime = "image/jpeg", width = 4, height = 3, thumb = null, file = "f", state = state, doneChunks = done)
        assertEquals(PhotoState.Uploading, photoUi(a(AttachmentState.UPLOADING, 2)).state)
        assertEquals(.5f, photoUi(a(AttachmentState.UPLOADING, 2)).progress!!, .001f)
        assertEquals(PhotoState.Ready, photoUi(a(AttachmentState.UPLOADED)).state)
        assertEquals(PhotoState.Ready, photoUi(a(AttachmentState.DOWNLOADED)).state)
        assertEquals(PhotoState.Remote, photoUi(a(AttachmentState.REMOTE)).state)
        assertEquals(PhotoState.Downloading, photoUi(a(AttachmentState.QUEUED, 1)).state)
        assertEquals(PhotoState.Failed, photoUi(a(AttachmentState.FAILED)).state)
        assertEquals(PhotoState.Gone, photoUi(a(AttachmentState.GONE)).state)
    }

    @Test fun previewCancelAndSingleSend() {
        var cancels = 0; val sends = mutableListOf<String>()
        val f = java.io.File.createTempFile("photo", ".jpg")
        compose.setContent { OtterTheme { PhotoPreview(f.path, "черновик", sending = false, onCancel = { cancels++ }, onSend = { sends += it }) } }
        compose.onNodeWithTag(PHOTO_PREVIEW_TAG).assertExists()
        compose.onNodeWithText("черновик").assertExists()
        compose.onNodeWithText("Отправить").performClick()
        assertEquals(listOf("черновик"), sends)
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(1, cancels)
    }

    @Test fun previewSendDisabledWhileSending() {
        val f = java.io.File.createTempFile("photo", ".jpg")
        compose.setContent { OtterTheme { PhotoPreview(f.path, "", sending = true, onCancel = {}, onSend = {}) } }
        compose.onNodeWithText("Отправить").assertIsNotEnabled()
    }

    @Test fun corruptPhotoSaysSo() {
        val src = object : PhotoSource {
            override suspend fun load(localId: Long, maxPx: Int): ImageBitmap? = null
            override suspend fun thumb(localId: Long): ImageBitmap? = null
            override suspend fun corrupt(localId: Long) = true
        }
        compose.setContent { OtterTheme { CompositionLocalProvider(LocalPhotoSource provides src) {
            PhotoContent(1L, PhotoUi(800, 600, PhotoState.Ready), 240.dp, onDownload = {})
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Фото повреждено", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
