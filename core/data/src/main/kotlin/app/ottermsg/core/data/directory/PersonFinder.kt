package app.ottermsg.core.data.directory

import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.database.entity.ContactEntity
import javax.inject.Inject

/** A found account for the person card; [hasChat] turns «Написать» into «Открыть чат». */
data class FoundPerson(val contact: ContactEntity, val hasChat: Boolean)

/** `@username` → person card → chat (D137), for screens that need only these steps (a fake in ViewModel tests). */
interface PersonFinder {
    /** The exact lookup; [FoundPerson] for a hit, else the [UsernameLookup.Result] that says why not. */
    suspend fun find(typed: String): Pair<FoundPerson?, UsernameLookup.Result>
    /** The direct conversation with [userId] (created on first use). */
    suspend fun chatWith(userId: ByteArray): Long

    companion object {
        val NONE = object : PersonFinder {
            override suspend fun find(typed: String) = null to UsernameLookup.Result.Offline
            override suspend fun chatWith(userId: ByteArray): Long = error("no person finder")
        }
    }
}

class RealPersonFinder @Inject constructor(private val lookup: UsernameLookup, private val messages: MessageRepository) : PersonFinder {
    override suspend fun find(typed: String): Pair<FoundPerson?, UsernameLookup.Result> {
        val r = lookup.find(typed)
        return (if (r is UsernameLookup.Result.Found) FoundPerson(r.contact, messages.hasConversationWith(r.contact.userId)) else null) to r
    }
    override suspend fun chatWith(userId: ByteArray): Long = messages.conversationWith(userId)
}
