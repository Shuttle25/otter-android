package app.ottermsg.core.data.news

/** The running app's versionCode (bound in the app module from BuildConfig). */
fun interface InstalledVersion { fun code(): Int }

/** Opens the system installer for a verified APK (bound in the app module). Call on the main thread. */
fun interface UpdateInstaller { fun install(file: java.io.File) }
