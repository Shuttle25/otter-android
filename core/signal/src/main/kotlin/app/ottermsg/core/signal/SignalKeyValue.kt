package app.ottermsg.core.signal

/** Byte-level persistence for libsignal state; implemented over Room in core:data. */
interface SignalKeyValue {
    enum class Table(val id: Int) { IDENTITY(1), PREKEY(2), SIGNED_PREKEY(3), KYBER_PREKEY(4), SESSION(5), SENDER_KEY(6), KYBER_USED(7) }
    fun get(table: Table, key: String): ByteArray?
    fun put(table: Table, key: String, value: ByteArray)
    fun delete(table: Table, key: String)
    fun keys(table: Table): List<String>
}

class InMemorySignalKeyValue : SignalKeyValue {
    private val data = LinkedHashMap<Pair<SignalKeyValue.Table, String>, ByteArray>()
    @Synchronized override fun get(table: SignalKeyValue.Table, key: String) = data[table to key]?.copyOf()
    @Synchronized override fun put(table: SignalKeyValue.Table, key: String, value: ByteArray) { data[table to key] = value.copyOf() }
    @Synchronized override fun delete(table: SignalKeyValue.Table, key: String) { data.remove(table to key) }
    @Synchronized override fun keys(table: SignalKeyValue.Table) = data.keys.filter { it.first == table }.map { it.second }
    /** Copies everything into [target] (used after a successful registration). */
    @Synchronized fun copyInto(target: SignalKeyValue) = data.forEach { (k, v) -> target.put(k.first, k.second, v) }
}
