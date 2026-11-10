package app.ottermsg.feature.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.feature.onboarding.screens.PhraseConfirmScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class PhraseConfirmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsOneBasedPositionLabels() {
        val state = OnboardingUiState(step = OnboardingStep.CONFIRM, words = List(24) { "w$it" }, confirmPositions = listOf(2, 10, 23))
        compose.setContent { OtterTheme { PhraseConfirmScreen(state, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Слово №3").assertExists()
        compose.onNodeWithText("Слово №11").assertExists()
        compose.onNodeWithText("Слово №24").assertExists()
        compose.onNodeWithText("Готово").assertIsNotEnabled() // empty inputs
    }
}
