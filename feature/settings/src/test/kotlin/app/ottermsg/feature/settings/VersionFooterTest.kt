package app.ottermsg.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class VersionFooterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsVersion() {
        compose.setContent { OtterTheme { VersionFooter("Otter 0.5.0 (abc1234)") } }
        compose.onNodeWithText("Otter 0.5.0 (abc1234)").assertIsDisplayed()
    }
}
