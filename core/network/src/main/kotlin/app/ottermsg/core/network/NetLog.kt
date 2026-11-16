package app.ottermsg.core.network

/** One connectivity observation (D74); the store merges repeats. */
data class NetLogEntry(val nodeLabel: String, val endpoint: String, val result: FailureClass, val detail: String = "", val rttMs: Long = 0)

/** The phone's local connectivity log (D74), persisted by core:data. */
interface NetLog {
    suspend fun add(e: NetLogEntry)
    object None : NetLog { override suspend fun add(e: NetLogEntry) {} }
}

/** "wss://host:27443" without the secret path, for the log. */
fun Candidate.logName(): String = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') +
    (via?.let { " via " + it.logName() } ?: "")
