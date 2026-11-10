package app.ottermsg.core.data.signal

import app.ottermsg.core.database.dao.SignalKvDao
import app.ottermsg.core.database.entity.SignalKvEntity
import app.ottermsg.core.signal.SignalKeyValue

class RoomSignalKeyValue(private val dao: SignalKvDao) : SignalKeyValue {
    override fun get(table: SignalKeyValue.Table, key: String) = dao.get(table.id, key)
    override fun put(table: SignalKeyValue.Table, key: String, value: ByteArray) = dao.put(SignalKvEntity(table.id, key, value))
    override fun delete(table: SignalKeyValue.Table, key: String) = dao.delete(table.id, key)
    override fun keys(table: SignalKeyValue.Table) = dao.keys(table.id)
}
