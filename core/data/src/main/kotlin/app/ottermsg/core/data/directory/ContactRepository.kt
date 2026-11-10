package app.ottermsg.core.data.directory

import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(private val contacts: ContactDao) {
    /** Family members (never includes the local user: DirectorySync skips self). */
    fun observeContacts(): Flow<List<ContactEntity>> = contacts.observeAll()
    suspend fun get(userId: ByteArray): ContactEntity? = contacts.get(userId)
}
