package app.ottermsg.feature.conversation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `@username` mentions in message text (M9.5, D137). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class UsernameLinksTest {
    @get:Rule val compose = createComposeRule()

    private fun names(t: String) = findUsernames(t).map { t.substring(it) }

    @Test fun findsOnlyStandaloneUsernames() {
        val t = "Пиши @vera_k или @Ivan#1a2b3c4d, mail a@b.c и @no"
        assertEquals(listOf("@vera_k", "@Ivan#1a2b3c4d"), names(t))
        assertEquals(emptyList<String>(), names("vera_k@example.org"))
        assertEquals(emptyList<String>(), names("@verasмысл")) // runs into a word
        assertEquals(listOf("@vera"), names("(@vera)"))
        assertEquals(emptyList<String>(), names("@@vera"))
    }

    @Test fun mentionIsATalkBackButtonThatLooksUp() {
        val tapped = mutableListOf<String>()
        val m = MessageUi(1, outgoing = false, text = "Пиши @vera_k", sentMs = 0, status = DeliveryStatus.DELIVERED, system = false,
            authorName = "Мама")
        compose.setContent { OtterTheme { MessageBubble(m, firstInGroup = true, onUsername = { tapped += it }) } }
        compose.onNodeWithContentDescription("Открыть @vera_k")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("@vera_k"), tapped)
        // the bubble still reads as one unit with the whole text
        compose.onNode(SemanticsMatcher("bubble") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().contains("Мама, Пиши @vera_k")
        }).assertExists()
    }

    @Test fun systemLinesStayPlain() {
        val m = MessageUi(2, outgoing = false, text = "@vera_k", sentMs = 0, status = DeliveryStatus.DELIVERED, system = true, authorName = "")
        compose.setContent { OtterTheme { MessageBubble(m, firstInGroup = true) } }
        compose.onNodeWithContentDescription("Открыть @vera_k").assertDoesNotExist()
    }
}
