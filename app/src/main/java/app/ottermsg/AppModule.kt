package app.ottermsg

import app.ottermsg.core.data.di.AllowPlaintext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Plain ws:// endpoints only in debug builds; release apps drop WS_PLAIN from the manifest. */
    @Provides @AllowPlaintext fun allowPlaintext(): Boolean = BuildConfig.ALLOW_PLAINTEXT
}
