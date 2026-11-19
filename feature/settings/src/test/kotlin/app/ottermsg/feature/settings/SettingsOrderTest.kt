package app.ottermsg.feature.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/** Settings is one plain list without section headers (owner 2026-11-16): rows in the guide's order (§10, D124), the
 *  version footer last; the new rows open their screens. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class SettingsOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun vm(theme: Int = 0) = SettingsViewModel(MutableStateFlow(null), MutableStateFlow(ConnectionState.Connecting),
        emptyFlow(), {}, themeMode = flowOf(theme))

    @Test fun noSectionHeadersRowsInOrderFooterLast() {
        compose.setContent { OtterTheme { SettingsRoute(onBack = {}, onInvite = {}, onProfile = {}, versionText = "Otter 0.10.1 (abc1234)", vm = vm()) } }
        fun title(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
        // the only heading is the top bar's «Настройки»
        assertEquals(listOf("Настройки"), compose.onAllNodes(isHeading(), useUnmergedTree = true).fetchSemanticsNodes().map(::title))
        for (gone in listOf("Профиль", "Семья", "Безопасность", "Подключение"))
            compose.onNodeWithText(gone, useUnmergedTree = true).assertDoesNotExist()
        // these are row titles now, no longer also section headers
        for (row in listOf("Уведомления", "Оформление", "Фото и память"))
            compose.onAllNodesWithText(row, useUnmergedTree = true).assertCountEquals(1)
        val rows = listOf("Пригласить в семью", "Уведомления", "Оформление", "Фото и память", "Блокировка приложения",
            "Запрет скриншотов", "Показывать меня в каталоге сети", "Резервная копия", "Фраза восстановления", "Подключаемся…", "Звонки только через сервер",
            "Otter 0.10.1 (abc1234)")
        val ys = rows.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y }
        assertEquals(rows, rows.zip(ys).sortedBy { it.second }.map { it.first })
        assertTrue("rows in order, each below the last: $ys", ys.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun allRowTitlesLineUp() {
        compose.setContent { OtterTheme { SettingsRoute(onBack = {}, onInvite = {}, onProfile = {}, vm = vm()) } }
        fun x(t: String) = compose.onNodeWithText(t, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val left = x("Уведомления")
        for (t in listOf("Пригласить в семью", "Блокировка приложения", "Запрет скриншотов", "Показывать меня в каталоге сети", "Резервная копия",
            "Звонки только через сервер")) assertEquals(t, left, x(t), 0.5f)
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

    /** «Показывать меня в каталоге сети» (D136): a switch, off by default, with the subtitle; a tap saves it. */
    @Test fun catalogRowIsAnOffSwitchThatSaves() {
        val stored = MutableStateFlow(false)
        val saved = mutableListOf<Boolean>()
        val vm = SettingsViewModel(MutableStateFlow(null), MutableStateFlow(ConnectionState.Connecting), emptyFlow(), {},
            listed = stored, saveListed = { saved += it; stored.value = it })
        compose.setContent { OtterTheme { SettingsRoute(onBack = {}, onInvite = {}, onProfile = {}, vm = vm) } }
        compose.onNodeWithText("Узлы и администратор всё равно видят всех участников", useUnmergedTree = true).assertExists()
        val row = compose.onNode(hasText("Показывать меня в каталоге сети") and isToggleable())
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assertIsOff().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), saved)
        row.assertIsOn()
    }
}
