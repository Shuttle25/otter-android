package app.ottermsg.feature.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D124: Settings sections in the guide's order (§10), the new rows, the version footer last. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class SettingsOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun vm(theme: Int = 0) = SettingsViewModel(MutableStateFlow(null), MutableStateFlow(ConnectionState.Connecting),
        emptyFlow(), {}, themeMode = flowOf(theme))

    @Test fun headingsInGuideOrderAndFooterLast() {
        compose.setContent { OtterTheme { SettingsRoute(onBack = {}, onInvite = {}, onProfile = {}, versionText = "Otter 0.10.1 (abc1234)", vm = vm()) } }
        fun title(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
        // section headings only: the top bar's «Настройки» is a heading too
        val headings = compose.onAllNodes(isHeading()).fetchSemanticsNodes().filter { title(it) != "Настройки" }
        val titles = headings.map(::title)
        assertEquals(listOf("Профиль", "Семья", "Уведомления", "Оформление", "Фото и память", "Безопасность", "Подключение"), titles)
        val footer = compose.onNodeWithText("Otter 0.10.1 (abc1234)").fetchSemanticsNode().positionInRoot.y
        assertTrue(headings.all { it.positionInRoot.y < footer })
    }

    @Test fun newRowsOpenTheirScreens() {
        var appearance = 0; var phrase = 0
        compose.setContent { OtterTheme {
            SettingsRoute(onBack = {}, onInvite = {}, onProfile = {}, onAppearance = { appearance++ }, onPhrase = { phrase++ }, vm = vm(theme = 2))
        } }
        compose.onNodeWithText("Тёмная").performScrollTo().performClick() // the Оформление row's subtitle is the current mode
        compose.onNodeWithText("Фраза восстановления").performScrollTo().performClick()
        assertEquals(1, appearance); assertEquals(1, phrase)
    }
}
