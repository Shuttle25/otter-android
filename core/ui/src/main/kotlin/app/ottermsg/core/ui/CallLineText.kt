package app.ottermsg.core.ui

import android.content.res.Resources

/**
 * Text of a call line (D80) in the chat and the chat-list preview. [outcome] is CallOutcome.code (core:data):
 * 1 outgoing, 2 incoming, 3 missed, 4 declined, 5 no answer, 6 busy, 7 failed.
 */
fun callLineText(res: Resources, outcome: Int, durationS: Long): String {
    val d = "%d:%02d".format(durationS / 60, durationS % 60)
    return when (outcome) {
        1 -> res.getString(R.string.call_line_outgoing, d)
        2 -> res.getString(R.string.call_line_incoming, d)
        3 -> res.getString(R.string.call_line_missed)
        4 -> res.getString(R.string.call_line_declined)
        5 -> res.getString(R.string.call_line_no_answer)
        6 -> res.getString(R.string.call_line_busy)
        else -> res.getString(R.string.call_line_failed)
    }
}
