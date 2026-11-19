package app.ottermsg.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Поделиться именем пользователя» (M9.5): the profile row shares `@username`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class ProfileShareTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shareRowCallsShare() {
        var shares = 0
        compose.setContent { OtterTheme {
            ProfileScreen(ProfileInfo(ByteArray(16), "vera", "Вера"), pending = false, onBack = {}, onEditName = {}, onShareUsername = { shares++ })
        } }
        compose.onNodeWithText("Поделиться именем пользователя").performScrollTo().performClick()
        assertEquals(1, shares)
    }
}
