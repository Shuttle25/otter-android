package app.ottermsg

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ottermsg.core.ui.components.ILLUSTRATION_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import app.ottermsg.feature.onboarding.R as OnboardingR

/**
 * Runs the real app on a device/emulator (what Robolectric missed in M2: a missing permission crashed the app at start).
 * Needs a fresh install: `adb uninstall app.ottermsg` first (see docs/09 "M3 notes").
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshInstallShowsWelcome() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(ctx.getString(OnboardingR.string.onb_welcome_join))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(ctx.getString(OnboardingR.string.onb_welcome_title)).assertExists()
        compose.onNodeWithTag(ILLUSTRATION_TAG).assertExists()
    }

    @Test fun networkPermissionsGrantedAtInstall() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (p in listOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE)) {
            assertEquals(p, PackageManager.PERMISSION_GRANTED, ctx.packageManager.checkPermission(p, ctx.packageName))
        }
    }
}
