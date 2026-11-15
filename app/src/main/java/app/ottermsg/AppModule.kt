package app.ottermsg

import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.news.InstalledVersion
import app.ottermsg.core.data.news.UpdateInstaller
import app.ottermsg.core.data.push.PushTokenSource
import app.ottermsg.push.FirebasePushTokens
import app.ottermsg.update.ApkInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Plain ws:// endpoints only in debug builds; release apps drop WS_PLAIN from the manifest. */
    @Provides @AllowPlaintext fun allowPlaintext(): Boolean = BuildConfig.ALLOW_PLAINTEXT
    /** «Обновить» shows only for a newer versionCode (D68). */
    @Provides fun installedVersion(): InstalledVersion = InstalledVersion { BuildConfig.VERSION_CODE }
    @Provides fun updateInstaller(i: ApkInstaller): UpdateInstaller = i
    /** «Пробуждение через Google» deletes and re-fetches the FCM token (D94). */
    @Provides fun pushTokens(f: FirebasePushTokens): PushTokenSource = f
}
