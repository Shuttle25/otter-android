package app.ottermsg.core.data.group

import app.ottermsg.core.data.bs
import app.ottermsg.proto.v1.GroupEvent
import app.ottermsg.proto.v1.GroupState
import com.google.protobuf.ByteString

/** System events for an accepted state change; pure (stored as messages, rendered by the UI, never as text). */
object GroupEvents {
    /**
     * Members of [old] that [new] replaces by their re-keyed account (D112): old id → new id, for pairs in [rekeys] whose
     * old id left and whose new id arrived. They get a «Ключ безопасности изменился» line instead of removed/added.
     */
    fun swapped(old: GroupState?, new: GroupState, rekeys: Map<ByteString, ByteString>): Map<ByteString, ByteString> =
        old?.membersList.orEmpty().filter { it !in new.membersList }
            .mapNotNull { o -> rekeys[o]?.takeIf { it in new.membersList && it !in old!!.membersList }?.let { o to it } }.toMap()

    fun between(
        old: GroupState?, new: GroupState, signer: ByteArray, me: ByteArray, rekeys: Map<ByteString, ByteString> = emptyMap(),
    ): List<GroupEvent> {
        val actor = bs(signer)
        fun ev(type: GroupEvent.Type, targets: Collection<ByteString> = emptyList(), name: String = "") =
            GroupEvent.newBuilder().setType(type).setActor(actor).addAllTargets(targets).setName(name).build()
        val meBs = bs(me)
        if (old == null) {
            return if (new.version == 1L) listOf(ev(GroupEvent.Type.CREATED, name = new.name))
            else listOf(ev(GroupEvent.Type.ADDED, listOf(meBs))) // joined later: we only know who vouched for the state
        }
        val out = mutableListOf<GroupEvent>()
        val swaps = swapped(old, new, rekeys)
        val added = new.membersList - old.membersList.toSet() - swaps.values.toSet()
        val removed = old.membersList - new.membersList.toSet() - swaps.keys
        if (added.isNotEmpty()) out += ev(GroupEvent.Type.ADDED, added)
        if (removed.isNotEmpty()) {
            if (removed == listOf(actor)) out += ev(GroupEvent.Type.LEFT)
            else {
                val others = removed.filterNot { it == meBs }
                if (others.isNotEmpty()) out += ev(GroupEvent.Type.REMOVED, others)
                if (meBs in removed) out += ev(GroupEvent.Type.YOU_WERE_REMOVED)
            }
        }
        if (new.name != old.name) out += ev(GroupEvent.Type.RENAMED, name = new.name)
        val granted = new.adminsList - old.adminsList.toSet() - swaps.filterKeys { it in old.adminsList }.values.toSet()
        val revoked = old.adminsList.filter { it in new.membersList && it !in new.adminsList }
        if (granted.isNotEmpty()) out += ev(GroupEvent.Type.ADMIN_GRANTED, granted)
        if (revoked.isNotEmpty()) out += ev(GroupEvent.Type.ADMIN_REVOKED, revoked)
        return out
    }
}
