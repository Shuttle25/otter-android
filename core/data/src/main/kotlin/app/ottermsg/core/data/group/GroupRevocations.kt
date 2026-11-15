package app.ottermsg.core.data.group

import androidx.room.withTransaction
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.directory.Membership
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * D104: a revoked member must not read new group messages. In every group whose state still lists the member, this
 * phone rotates its own sender key at once ([app.ottermsg.core.data.messaging.GroupSender] also never addresses revoked
 * contacts), and the group's non-revoked admin with the lowest user id removes the member from the signed state, so the
 * change reaches phones that missed the revocation. Each (member, group) pair is handled once per phone (persisted).
 * Called after every directory sync.
 */
@Singleton
class GroupRevocations @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val groups: GroupStore,
    private val settings: Settings,
) {
    private val lock = Mutex()

    suspend fun handle() = lock.withLock {
        val me = accounts.require().userId
        val done = settings.revocationsHandled()
        val contacts = db.contactDao()
        val dao = db.groupDao()
        for (c in contacts.all().filter { it.revoked }) {
            val handled = mutableListOf<String>()
            for (gid in dao.groupsWithMember(c.userId)) {
                val key = "${c.userId.hex()}:${gid.hex()}"
                if (key in done) continue
                db.withTransaction {
                    dao.deleteMyDistribution(gid) // the next group send makes a fresh key, shared with the rest only
                    dao.clearShared(gid)
                }
                val admins = dao.members(gid).filter { it.admin }.map { it.userId }
                    .filter { a -> a.contentEquals(me) || contacts.get(a)?.revoked != true }
                val designated = admins.minWithOrNull(Membership::compareUnsigned)
                if (designated != null && designated.contentEquals(me) && dao.get(gid)?.iAmMember == true) {
                    try { groups.removeMember(gid, c.userId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log.log(Level.WARNING, "could not remove a revoked member", e) }
                }
                handled += key
            }
            settings.addRevocationsHandled(handled)
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object { private val log = Logger.getLogger("GroupRevocations") }
}
