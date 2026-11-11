package app.ottermsg.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ottermsg.core.ui.R
import app.ottermsg.proto.v1.GroupEvent

/** Decodes a stored group system event (messages.system_data); null if malformed. */
fun parseGroupEvent(bytes: ByteArray?): GroupEvent? = bytes?.let { runCatching { GroupEvent.parseFrom(it) }.getOrNull() }

/**
 * Text of a group system event (guide §7: rendered from data, never stored as text). Names are not declined and
 * verbs use the "(а)" form, because Russian needs the actor's gender, which Otter does not know.
 */
@Composable
fun groupEventText(e: GroupEvent, me: ByteArray, nameOf: (ByteArray) -> String): String {
    val actorIsMe = e.actor.toByteArray().contentEquals(me)
    val actor = nameOf(e.actor.toByteArray())
    val targetsAreMe = e.targetsCount == 1 && e.getTargets(0).toByteArray().contentEquals(me)
    val you = stringResource(R.string.ui_ev_you)
    val targets = e.targetsList.joinToString(", ") { t -> if (t.toByteArray().contentEquals(me)) you else nameOf(t.toByteArray()) }
    return when (e.type) {
        GroupEvent.Type.CREATED -> if (actorIsMe) stringResource(R.string.ui_ev_created_me, e.name) else stringResource(R.string.ui_ev_created, actor, e.name)
        GroupEvent.Type.ADDED -> when {
            targetsAreMe -> stringResource(R.string.ui_ev_added_you, actor)
            actorIsMe -> stringResource(R.string.ui_ev_added_me, targets)
            else -> stringResource(R.string.ui_ev_added, actor, targets)
        }
        GroupEvent.Type.REMOVED -> if (actorIsMe) stringResource(R.string.ui_ev_removed_me, targets) else stringResource(R.string.ui_ev_removed, actor, targets)
        GroupEvent.Type.YOU_WERE_REMOVED -> stringResource(R.string.ui_ev_you_removed, actor)
        GroupEvent.Type.LEFT -> if (actorIsMe) stringResource(R.string.ui_ev_left_me) else stringResource(R.string.ui_ev_left, actor)
        GroupEvent.Type.RENAMED -> if (actorIsMe) stringResource(R.string.ui_ev_renamed_me, e.name) else stringResource(R.string.ui_ev_renamed, actor, e.name)
        GroupEvent.Type.ADMIN_GRANTED -> when {
            targetsAreMe -> stringResource(R.string.ui_ev_admin_you, actor)
            actorIsMe -> stringResource(R.string.ui_ev_admin_me, targets)
            else -> stringResource(R.string.ui_ev_admin, actor, targets)
        }
        GroupEvent.Type.ADMIN_REVOKED -> if (actorIsMe) stringResource(R.string.ui_ev_unadmin_me, targets) else stringResource(R.string.ui_ev_unadmin, actor, targets)
        GroupEvent.Type.UNDECRYPTABLE -> stringResource(R.string.ui_ev_undecryptable, actor)
        else -> stringResource(R.string.ui_ev_unknown)
    }
}
