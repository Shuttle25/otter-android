package app.ottermsg.core.data.di

import javax.inject.Qualifier

/** Long-lived scope for work that must outlive a screen (SupervisorJob + Dispatchers.Default). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

/** true only in debug builds: allows plain ws:// endpoints (provided by :app from BuildConfig). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AllowPlaintext
