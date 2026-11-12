package app.ottermsg.update

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApkInstallerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val apk = File(app.cacheDir, "apk/abc.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

    @Test fun withoutPermissionOpensTheUnknownSourcesSetting() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        ApkInstaller(app).install(apk)
        val i = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, i.action)
        assertEquals("package:${app.packageName}", i.dataString)
    }

    @Test fun withPermissionOpensTheInstallerThroughTheFileProvider() {
        Robolectric.buildContentProvider(ApkFileProvider::class.java).create(android.content.pm.ProviderInfo().apply {
            authority = "${app.packageName}.files"; grantUriPermissions = true; exported = false
        })
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        ApkInstaller(app).install(apk)
        val i = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("application/vnd.android.package-archive", i.type)
        assertEquals("content://${app.packageName}.files/apk/abc.apk", i.dataString)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
