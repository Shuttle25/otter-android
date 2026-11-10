package app.ottermsg.core.database.key

/** Wraps small secrets (the DB key) with a device-bound key. */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}
