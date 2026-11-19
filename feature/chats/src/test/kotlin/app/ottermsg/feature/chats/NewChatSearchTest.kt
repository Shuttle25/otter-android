package app.ottermsg.feature.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.ui.components.PersonCard
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Написать» search (M9.5, D136/D137): the catalog filtered as you type, «Найти @name» for an unknown username. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NewChatSearchTest {
    @get:Rule val compose = createComposeRule()

    private fun contact(seed: Int, name: String, username: String) = ContactEntity(ByteArray(16) { seed.toByte() }, username, name,
        ByteArray(32), ByteArray(33), verified = false, identityChanged = false, revoked = false, entryVersion = 1, listed = true)
    private val anna = contact(1, "Анна", "anna")
    private val boris = contact(2, "Борис", "boris")

    @Test fun typingFiltersAndOffersTheExactLookup() {
        var query by mutableStateOf("")
        var finds = 0
        compose.setContent { OtterTheme {
            NewChatScreen(searchList(listOf(anna, boris), query), query, { query = it }, onFind = { finds++ }, searching = false,
                failed = null, onPick = {}, onBack = {}, onInvite = {}, onNewGroup = {})
        } }
        compose.onNodeWithText("Борис").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("ann")
        compose.onNodeWithText("Анна").assertExists()
        compose.onNodeWithText("Борис").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("@zoya_k")
        compose.onNodeWithText("Найти @zoya_k").performClick()
        assertEquals(1, finds)
        // an exact catalog match needs no lookup
        compose.onNode(hasSetTextAction()).performTextReplacement("@Boris")
        compose.onNodeWithText("Борис").assertExists()
        compose.onNodeWithText("Найти @boris").assertDoesNotExist()
    }

    @Test fun failedLookupSaysWhy() {
        compose.setContent { OtterTheme {
            NewChatScreen(emptyList(), "zoya_k", {}, onFind = {}, searching = false, failed = UsernameLookup.Result.Limited,
                onPick = {}, onBack = {}, onInvite = {}, onNewGroup = {})
        } }
        compose.onNodeWithText("Слишком много поисков, попробуйте позже").assertExists()
    }

    @Test fun offerRules() {
        assertNull(findOffer(listOf(anna), "an")) // too short for a username
        assertNull(findOffer(listOf(anna), "@Anna"))
        assertEquals("anna", findOffer(listOf(boris), "@Anna "))
        assertNull(findOffer(emptyList(), "имя"))
        assertEquals("vera#1a2b3c4d", findOffer(emptyList(), "@vera#1a2b3c4d"))
    }

    @Test fun personCardWritesOrOpensTheChat() {
        var hasChat by mutableStateOf(false)
        var writes = 0
        compose.setContent { OtterTheme { PersonCard("Вера", "vera", ByteArray(16), hasChat, onWrite = { writes++ }) } }
        compose.onNodeWithText("Вера").assertExists()
        compose.onNodeWithText("@vera").assertExists()
        compose.onNodeWithText("Написать").performClick()
        assertEquals(1, writes)
        hasChat = true
        compose.onNodeWithText("Открыть чат").assertExists()
    }
}
