package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AssistantCalendarEntry(
    val startMinute: Int,
    val endMinute: Int,
    val content: String,
)

fun validateCalendarEntries(entries: List<AssistantCalendarEntry>): List<AssistantCalendarEntry> {
    require(entries.size <= 48) { "每天最多 48 项计划" }
    val sorted = entries.map {
        require(it.startMinute in 0..1439 && it.endMinute in 1..1440 && it.startMinute < it.endMinute) {
            "时间段必须在当天内，且结束时间晚于开始时间"
        }
        val text = it.content.trim()
        require(text.isNotEmpty() && text.length <= 500) { "计划内容须为 1–500 个字符" }
        it.copy(content = text)
    }.sortedBy { it.startMinute }
    require(sorted.zipWithNext().all { (a, b) -> a.endMinute <= b.startMinute }) { "计划时间段不能重叠" }
    return sorted
}

/** Half-open intervals ensure boundary activities match exactly one plan, or remain unplanned. */
fun calendarPlanIndex(entries: List<AssistantCalendarEntry>, minute: Int): Int =
    entries.indexOfFirst { minute >= it.startMinute && minute < it.endMinute }

fun calendarTimeLabel(minute: Int): String {
    require(minute in 0..1440)
    return "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
}

fun parseCalendarTime(text: String, allowEndOfDay: Boolean = false): Int {
    require(Regex("\\d{2}:\\d{2}").matches(text)) { "请输入 HH:mm 格式时间" }
    val hour = text.substring(0, 2).toInt()
    val minute = text.substring(3, 5).toInt()
    require((hour in 0..23 && minute in 0..59) || (allowEndOfDay && hour == 24 && minute == 0)) {
        "时间范围为 00:00–23:59，结束时间可为 24:00"
    }
    return hour * 60 + minute
}
