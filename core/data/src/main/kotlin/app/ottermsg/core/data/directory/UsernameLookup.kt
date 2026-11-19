package app.ottermsg.core.data.directory

import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.network.LookupLimitException
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.OldNodeException
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Status
import java.io.IOException
import javax.inject.Inject

/**
 * Exact `@username` lookup (D137): the phone's own store first, then the node. The node's answer goes through the
 * normal directory admission (signatures, invite chain, D104) and must then hold an account with exactly that
 * username; anything else is «not found». A found account is stored but joins the catalog only once a chat exists (D136).
 */
class UsernameLookup @Inject constructor(
    private val contacts: ContactDao, private val directory: DirectorySync, private val online: OnlineApi,
) {
    sealed interface Result {
        data class Found(val contact: ContactEntity) : Result
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
        local(q)?.let { return Result.Found(it) }
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
        return local(q)?.let { Result.Found(it) } ?: Result.NotFound
    }

    /** A stored, live account whose signed username (and user id, for a tagged name) is exactly [q]. */
    private suspend fun local(q: String): ContactEntity? =
        contacts.allByUsername(q.substringBefore('#')).firstOrNull { !it.revoked && it.entryVersion > 0 && matches(it, q) }

    companion object {
        /** A username, optionally with the clash tag: the first 8 hex digits of the user id (`name#1a2b3c4d`). */
        val VALID = Regex("^[a-z0-9_]{3,32}(#[0-9a-f]{8})?$")

        /** "@Vera " → "vera". */
        fun normalize(typed: String): String = typed.trim().removePrefix("@").lowercase()

        /** True when [c] is exactly the account named by the normalized query [q]. */
        fun matches(c: ContactEntity, q: String): Boolean {
            if (c.username != q.substringBefore('#')) return false
            val tag = q.substringAfter('#', "")
            return tag.isEmpty() || c.userId.joinToString("") { "%02x".format(it) }.startsWith(tag)
        }
    }
}
