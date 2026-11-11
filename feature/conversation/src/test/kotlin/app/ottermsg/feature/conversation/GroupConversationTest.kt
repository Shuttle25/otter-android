package app.ottermsg.feature.conversation

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.data.messaging.GroupView
import app.ottermsg.core.database.dao.RecipientStatus
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.proto.v1.GroupEvent
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class GroupConversationTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Etc/GMT-3")
    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private val me = id(1); private val boris = id(2); private val vera = id(3); private val gid = id(9)
    private val t0 = 1_800_000_000_000L
    private val contacts = listOf(
        ContactEntity(boris, "boris", "Борис", ByteArray(32), ByteArray(33), false, false, false, 1),
        ContactEntity(vera, "vera", "Вера", ByteArray(32), ByteArray(33), false, false, false, 1),
    )
    private val conv = ConversationEntity(5, null, "Семья", t0, groupId = gid, type = ConversationType.GROUP)
    private fun group(member: Boolean = true) = GroupView(GroupEntity(gid, "Семья", true, 2, byteArrayOf(), null, member, me),
        listOf(GroupMemberEntity(gid, me, true), GroupMemberEntity(gid, boris, false), GroupMemberEntity(gid, vera, false)))
    private fun msg(n: Long, from: ByteArray, text: String, kind: Int = MessageKind.TEXT, data: ByteArray? = null) =
        MessageEntity(n, ByteArray(16) { n.toByte() }, 5, from, from.contentEquals(me), t0 + n * 1000, t0, text, MessageStatus.DELIVERED,
            kind, systemData = data)

    private val left = GroupEvent.newBuilder().setType(GroupEvent.Type.LEFT).setActor(ByteString.copyFrom(vera)).build()
    private val msgs = listOf(msg(1, boris, "раз"), msg(2, boris, "два"), msg(3, vera, "три"),
        msg(4, vera, "", MessageKind.SYSTEM_GROUP, left.toByteArray()), msg(5, vera, "четыре"))

    @Test fun mappingMarksSeriesByAuthorAndEvents() {
        val ui = conversationUi(conv, msgs, group(), contacts, me, zone)
        val bubbles = ui.items.filterIsInstance<ConversationItem.Bubble>()
        assertEquals(listOf(true, false, true, true, true), bubbles.map { it.firstInGroup })
        assertEquals("Борис", bubbles[0].message.authorName)
        assertTrue(bubbles[3].message.isEvent)
        assertEquals(3, ui.memberCount); assertTrue(ui.isGroup); assertTrue(ui.family); assertTrue(ui.canWrite)
        assertFalse(conversationUi(conv, msgs, group(member = false), contacts, me, zone).canWrite)
    }

    @Test fun authorNameOnlyAtTheStartOfASeries() {
        val ui = conversationUi(conv, msgs, group(), contacts, me, zone)
        compose.setContent { OtterTheme { ConversationScreen(ui, "", false, {}, {}, {}, {}, {}) } }
        val shown = SemanticsMatcher("shows author") { it.config.getOrNull(ShowsAuthor) == true }
        val hidden = SemanticsMatcher("no author") { it.config.getOrNull(ShowsAuthor) == false }
        assertEquals(3, compose.onAllNodes(shown).fetchSemanticsNodes().size)  // раз, три, четыре (after the event)
        assertEquals(1, compose.onAllNodes(hidden).fetchSemanticsNodes().size) // два
        compose.onNodeWithText("Вера покинул(а) группу").assertExists()       // event chip, plain text for TalkBack
        compose.onNodeWithText("3 участника").assertExists()
    }

    @Test fun composerHiddenWhenNotAMember() {
        val ui = conversationUi(conv, msgs, group(member = false), contacts, me, zone)
        compose.setContent { OtterTheme { ConversationScreen(ui, "", false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Вы больше не участник этой группы").assertExists()
        compose.onNodeWithContentDescription("Отправить сообщение").assertDoesNotExist()
    }

    @Test fun messageInfoShowsTwoOfThree() {
        val m = msg(7, me, "привет")
        val rs = listOf(
            RecipientStatus(boris, "Борис", t0 + 10_000, t0 + 20_000),
            RecipientStatus(vera, "Вера", t0 + 10_000, null),
            RecipientStatus(id(4), "Гриша", null, null),
        )
        val ui = messageInfoUi(m, rs, group())
        assertEquals(2, ui.delivered); assertEquals(1, ui.read); assertEquals(3, ui.total)
        assertFalse(ui.rows.first { it.name == "Гриша" }.stillInGroup)
        compose.setContent { OtterTheme { MessageInfoScreen(ui) {} } }
        compose.onNodeWithText("Доставлено 2 из 3").assertExists()
        compose.onNodeWithText("Прочитано 1 из 3").assertExists()
        compose.onNodeWithText("Ожидает").assertExists()
        compose.onNodeWithText("не в группе").assertExists()
    }
}
