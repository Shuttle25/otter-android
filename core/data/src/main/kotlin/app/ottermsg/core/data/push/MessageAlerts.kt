package app.ottermsg.core.data.push

import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app is visible (ForegroundSync keeps it current). Separate from ForegroundSync to avoid a DI cycle:
 *  SyncEngine → receivers → MessageAlerts → notifier must not depend on the engine. */
@Singleton
class AppVisibility @Inject constructor() {
    @Volatile var visible: Boolean = false
}
