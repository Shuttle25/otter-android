package app.ottermsg.calls

import android.content.Context
import app.ottermsg.core.data.calls.CallAlerts
import app.ottermsg.core.data.calls.MediaEngine
import app.ottermsg.feature.calls.WebRtcEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CallModule {
    @Provides fun mediaEngines(@ApplicationContext c: Context): MediaEngine.Factory = MediaEngine.Factory { WebRtcEngine(c) }
    /** Replaced by CallNotifier (ringing, missed-call notifications) in Task 12. */
    @Provides fun callAlerts(): CallAlerts = CallAlerts.NONE
}
