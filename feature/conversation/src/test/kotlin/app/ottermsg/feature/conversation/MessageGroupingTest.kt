package app.ottermsg.feature.conversation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MessageGroupingTest {
    private val zone = ZoneId.of("Etc/GMT-3")
    private var id = 0L
    private fun m(out: Boolean, y: Int, mo: Int, d: Int, h: Int, min: Int, system: Boolean = false) =
        MessageUi(++id, out, "t$id", LocalDateTime.of(y, mo, d, h, min).atZone(zone).toInstant().toEpochMilli(), 1, system, "")

    private fun shape(items: List<ConversationItem>) = items.map {
        when (it) {
            is ConversationItem.DateChip -> "D${it.date.dayOfMonth}"
            is ConversationItem.Bubble -> (if (it.firstInGroup) "[" else "") + it.message.localId + (if (it.lastInGroup) "]" else "")
        }
    }

    @Test fun groupsByAuthorGapDateAndSystem() {
        val list = listOf(
            m(false, 2026, 11, 9, 23, 50), m(false, 2026, 11, 9, 23, 52), // same author, 2 min apart: one series
            m(false, 2026, 11, 10, 0, 1),                                // date change: new chip + series
            m(true, 2026, 11, 10, 0, 2),                                 // author change
            m(true, 2026, 11, 10, 0, 8),                                 // > 5 min gap
            m(true, 2026, 11, 10, 0, 9, system = true),                  // system message is its own series
            m(true, 2026, 11, 10, 0, 10),
        )
        assertEquals(listOf("D9", "[1", "2]", "D10", "[3]", "[4]", "[5]", "[6]", "[7]"), shape(groupMessages(list, zone)))
    }

    @Test fun exactlyFiveMinutesStaysInSeries() {
        val list = listOf(m(true, 2026, 11, 10, 10, 0), m(true, 2026, 11, 10, 10, 5))
        assertEquals(listOf("D10", "[1", "2]"), shape(groupMessages(list, zone)))
        assertEquals(LocalDate.of(2026, 11, 10), (groupMessages(list, zone)[0] as ConversationItem.DateChip).date)
    }

    @Test fun empty() = assertEquals(emptyList<ConversationItem>(), groupMessages(emptyList(), zone))
}
