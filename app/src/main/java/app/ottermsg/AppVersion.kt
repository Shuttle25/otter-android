package app.ottermsg

/** Settings footer text (D63). */
object AppVersion {
    fun text(versionName: String, gitHash: String): String = "Otter $versionName (${gitHash.ifEmpty { "dev" }})"
    val current: String get() = text(BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH)
}
