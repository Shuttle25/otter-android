package app.ottermsg.core.data.directory

import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(private val contacts: ContactDao) {
    /** Family members (never includes the local user: DirectorySync skips self). */
    /** «Написать» (D136): the catalog, not every stored entry. */
    fun observeContacts(): Flow<List<ContactEntity>> = contacts.observeCatalog()
    /** Display names by user id (hex) of every account ever accepted, removed and re-keyed ones too (old messages keep their author). */
    fun observeNames(): Flow<Map<String, String>> = contacts.observeAllKnown().map { cs -> cs.associate { it.userId.hex() to it.displayName } }
    suspend fun get(userId: ByteArray): ContactEntity? = contacts.get(userId)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
