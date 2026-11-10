package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantCalendarEntry
import me.rerere.rikkahub.data.model.calendarPlanIndex
import me.rerere.rikkahub.data.model.calendarTimeLabel
import me.rerere.rikkahub.data.model.parseCalendarTime
import me.rerere.rikkahub.data.model.validateCalendarEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssistantCalendarGeneratorTest {
    @Test
    fun `AI response is sorted trimmed and may end at midnight`() {
        val entries = parseCalendarEntries(
            """{"entries":[{"start":"23:00","end":"24:00","content":"休息"},{"start":"08:00","end":"09:00","content":" 早餐 "}]}"""
        )
        assertEquals(480, entries.first().startMinute)
        assertEquals("早餐", entries.first().content)
        assertEquals(1440, entries.last().endMinute)
        assertEquals("24:00", calendarTimeLabel(entries.last().endMinute))
    }

    @Test
    fun `complete markdown fence is accepted`() {
        val json = """{"entries":[{"start":"09:00","end":"10:00","content":"阅读"}]}"""
        assertEquals(parseCalendarEntries(json), parseCalendarEntries("```json\n$json\n```"))
        rejects { parseCalendarEntries("```json\n$json") }
    }

    @Test
    fun `empty AI response and malformed partial entries are rejected as a whole`() {
        rejects { parseCalendarEntries("""{"entries":[]}""") }
        rejects { parseCalendarEntries("""{"entries":[{"start":"09:00","end":"10:00","content":"阅读"},{"start":"10:00","content":"缺结束"}]}""") }
        rejects { parseCalendarEntries("""{"entries":[{"start":"09:00","end":"09:00","content":"零长度"}]}""") }
    }

    @Test
    fun `invalid times overlap and blank content cannot be stored`() {
        listOf("24:01", "25:00", "12:60", "9:00", "-1:00").forEach { text ->
            rejects { parseCalendarTime(text, true) }
        }
        rejects { parseCalendarTime("24:00") }
        rejects { validateCalendarEntries(listOf(AssistantCalendarEntry(60, 120, " "))) }
        rejects { validateCalendarEntries(listOf(AssistantCalendarEntry(60, 120, "A"), AssistantCalendarEntry(119, 180, "B"))) }
        rejects { validateCalendarEntries(List(49) { AssistantCalendarEntry(it, it + 1, "计划") }) }
    }

    @Test
    fun `manual empty day remains valid and adjacent plans are allowed`() {
        assertTrue(validateCalendarEntries(emptyList()).isEmpty())
        assertEquals(2, validateCalendarEntries(listOf(AssistantCalendarEntry(0, 60, "A"), AssistantCalendarEntry(60, 120, "B"))).size)
    }

    @Test
    fun `actual activity belongs to a single half open plan interval`() {
        val entries = listOf(AssistantCalendarEntry(900, 960, "健身"), AssistantCalendarEntry(960, 1020, "阅读"))
        assertEquals(0, calendarPlanIndex(entries, 922))
        assertEquals(1, calendarPlanIndex(entries, 960))
        assertEquals(-1, calendarPlanIndex(entries, 899))
        assertEquals(-1, calendarPlanIndex(entries, 1020))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected invalid calendar input to be rejected")
    }
}
