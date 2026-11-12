package app.ottermsg.notify

import app.ottermsg.core.data.push.MessageAlerts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {
    @Binds abstract fun alerts(n: MessageNotifier): MessageAlerts
}
