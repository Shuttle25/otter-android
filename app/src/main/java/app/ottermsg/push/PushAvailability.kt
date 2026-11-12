package app.ottermsg.push

import android.content.Context
import app.ottermsg.BuildConfig
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp

object PushAvailability {
    /** Firebase configured in this build, its default app initialised, and Google Play services usable. */
    fun check(context: Context): Boolean = BuildConfig.FIREBASE && FirebaseApp.getApps(context).isNotEmpty() &&
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}
