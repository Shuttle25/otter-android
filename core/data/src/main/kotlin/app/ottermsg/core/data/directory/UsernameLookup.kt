package app.ottermsg.core.data.directory

import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.network.LookupLimitException
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.OldNodeException
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import java.io.IOException
import javax.inject.Inject

/**
 * Exact `@username` lookup (D137): the phone's own store first, then the node. The node's answer goes through the
 * normal directory admission (signatures, invite chain, D104) and must then hold an account with exactly that
 * username; anything else is «not found». A found account is stored but joins the catalog only once a chat exists (D136).
 *
 * A plain name in a D40 clash belongs to the clash winner only (the node names the loser `name#<8 hex>`): when the store
 * holds more than one live account with that username, the node decides, not the store.
 */
class UsernameLookup @Inject constructor(
    private val contacts: ContactDao, private val directory: DirectorySync, private val online: OnlineApi,
    private val accounts: AccountRepository,
) {
    sealed interface Result {
        data class Found(val contact: ContactEntity) : Result
        /** The name is this phone's own account. */
        data object Self : Result
        data object NotFound : Result
        data object Limited : Result
        data object OldNode : Result
        data object Offline : Result
    }

    /** Through the live session, if there is one. */
    suspend fun find(typed: String): Result = find(online.current(), typed)

    suspend fun find(api: NodeApi?, typed: String): Result {
        val q = normalize(typed)
        if (!VALID.matches(q)) return Result.NotFound
        val name = q.substringBefore('#')
        val tagged = '#' in q
        val me = accounts.account.value
        val stored = live(name)
        val mine = me != null && me.username == name // the own account is not a contact
        if (tagged) {
            if (mine && matchesId(me!!.userId, q)) return Result.Self
            stored.firstOrNull { matches(it, q) }?.let { return Result.Found(it) }
        } else when (stored.size + (if (mine) 1 else 0)) {
            0 -> {}
            1 -> return if (mine) Result.Self else Result.Found(stored.single())
            else -> {} // a clash (D40): only the node knows the winner of the plain name
        }
        if (api == null) return Result.Offline
        val records = try {
            api.directoryLookup(q) ?: return Result.NotFound
        } catch (e: LookupLimitException) {
            return Result.Limited
        } catch (e: OldNodeException) {
            return Result.OldNode
        } catch (e: RpcException) {
            return if (e.status == Status.STATUS_INVALID) Result.NotFound else Result.Offline
        } catch (e: IOException) {
            return Result.Offline
        }
        directory.apply(records) // verified and admitted by the normal rules; ancestry stays out of the catalog
        if (tagged) return live(name).firstOrNull { matches(it, q) }?.let { Result.Found(it) } ?: Result.NotFound
        // the answer is one match plus its invite ancestry: the match is the entry with that username; if an ancestor
        // shares it too, the match is the one this phone did not have before
        val authors = records.mapNotNull { entryAuthor(it, name) }.distinct()
        val before = stored.map { it.userId.hex() }.toSet()
        val pick = authors.singleOrNull() ?: authors.filter { it !in before }.singleOrNull() ?: return Result.NotFound
        if (me != null && pick == me.userId.hex()) return Result.Self
        return live(name).firstOrNull { it.userId.hex() == pick }?.let { Result.Found(it) } ?: Result.NotFound
    }

    /** Stored, live accounts whose signed username is [name]. */
    private suspend fun live(name: String): List<ContactEntity> =
        contacts.allByUsername(name).filter { !it.revoked && it.entryVersion > 0 }

    /** The author (user id, hex) of [r] if it is a DIRECTORY_ENTRY with username [name]. */
    private fun entryAuthor(r: Record, name: String): String? = try {
        val b = RecordBody.parseFrom(r.body)
        if (b.type == RecordType.DIRECTORY_ENTRY && DirectoryEntry.parseFrom(b.payload).username == name) b.author.toByteArray().hex()
        else null
    } catch (e: com.google.protobuf.InvalidProtocolBufferException) { null }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** A username, optionally with the clash tag: the first 8 hex digits of the user id (`name#1a2b3c4d`). */
        val VALID = Regex("^[a-z0-9_]{3,32}(#[0-9a-f]{8})?$")

        /** "@Vera " → "vera". */
        fun normalize(typed: String): String = typed.trim().removePrefix("@").lowercase()

        /** True when [c] is exactly the account named by the normalized query [q]. */
        fun matches(c: ContactEntity, q: String): Boolean = c.username == q.substringBefore('#') && matchesId(c.userId, q)

        /** True when [q] has no clash tag or its tag is the start of [userId] in hex. */
        private fun matchesId(userId: ByteArray, q: String): Boolean {
            val tag = q.substringAfter('#', "")
            return tag.isEmpty() || userId.joinToString("") { "%02x".format(it) }.startsWith(tag)
        }
    }
}
