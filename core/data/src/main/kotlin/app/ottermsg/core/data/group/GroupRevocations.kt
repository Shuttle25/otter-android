package app.ottermsg.core.data.group

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
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
 * change reaches phones that missed the revocation.
 *
 * D112: a member re-keyed after a lost phone is swapped for its new account instead of removed, by the same single phone
 * (owner Q8: the lowest non-revoked admin; if the old account was the only admin, the lowest non-revoked member) once the
 * REKEY and the new account's entry are known. While a REVOCATION with reason "rekey" (step 1 of `otter-admin user
 * rekey`) waits for its REKEY, at most [REKEY_WAIT_MS], the member is not removed (keys still rotate at once). So the
 * removal and the swap never both act on one (member, group) pair: a member with a REKEY is only ever swapped.
 *
 * Each (member, group) pair is handled once per phone (persisted; the rotation separately, as `<pair>:rotated`).
 * Called after every directory sync.
 */
@Singleton
class GroupRevocations @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val groups: GroupRepository,
    private val applier: GroupApplier, private val settings: Settings, private val clock: Clock,
) {
    private val lock = Mutex()

    suspend fun handle() = lock.withLock {
        val me = accounts.require().userId
        val rekeys = db.rekeyDao().all().associateBy { it.oldUserId.hex() }
        // swaps that arrived before their REKEY (parked "unknown rekey"), or a re-keyed newcomer's group ("unknown group")
        if (rekeys.isNotEmpty()) applier.retryAllParked()
        val forRekey = settings.rekeyRevocations()
        val now = clock.now()
        val done = settings.revocationsHandled()
        val contacts = db.contactDao()
        val dao = db.groupDao()
        suspend fun live(u: ByteArray) = u.contentEquals(me) || contacts.get(u)?.revoked != true
        for (c in contacts.all().filter { it.revoked }) {
            val handled = mutableListOf<String>()
            val rekey = rekeys[c.userId.hex()]
            val successor = rekey?.newUserId?.takeIf { contacts.get(it) != null } // its entry is known: the swap can be signed
            val awaitingRekey = rekey == null && forRekey[c.userId.hex()]?.let { now - it < REKEY_WAIT_MS } == true
            for (gid in dao.groupsWithMember(c.userId)) {
                val key = "${c.userId.hex()}:${gid.hex()}"
                if (key in done) continue
                if ("$key:rotated" !in done) {
                    db.withTransaction {
                        dao.deleteMyDistribution(gid) // the next group send makes a fresh key, shared with the rest only
                        dao.clearShared(gid)
                    }
                    handled += "$key:rotated"
                }
                val members = dao.members(gid).filterNot { it.userId.contentEquals(c.userId) }
                val iAmMember = dao.get(gid)?.iAmMember == true
                val admin = members.filter { it.admin && live(it.userId) }.map { it.userId }.minWithOrNull(Membership::compareUnsigned)
                when {
                    successor != null -> {
                        val designated = admin ?: members.map { it.userId }.filter { live(it) }.minWithOrNull(Membership::compareUnsigned)
                        if (designated != null && designated.contentEquals(me) && iAmMember) {
                            try { groups.swapMember(gid, c.userId, successor) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { log.log(Level.WARNING, "could not swap a re-keyed member", e); continue }
                        }
                        handled += key
                    }
                    rekey != null || awaitingRekey -> {} // the swap comes when the REKEY and the new entry are known
                    else -> {
                        if (admin != null && admin.contentEquals(me) && iAmMember) {
                            try { groups.removeMember(gid, c.userId) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { log.log(Level.WARNING, "could not remove a revoked member", e) }
                        }
                        handled += key
                    }
                }
            }
            settings.addRevocationsHandled(handled)
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * How long a member revoked for a re-key stays listed (keys rotated, never addressed) waiting for its REKEY before
         * it is removed like any revoked member: the new install's invite lives 7 days, plus time to read the account code.
         */
        const val REKEY_WAIT_MS = 14L * 24 * 3600 * 1000
        private val log = Logger.getLogger("GroupRevocations")
    }
}
