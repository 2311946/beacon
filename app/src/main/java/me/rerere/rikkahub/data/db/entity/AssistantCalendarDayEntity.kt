package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "assistant_calendar_day",
    primaryKeys = ["assistant_id", "date"],
)
data class AssistantCalendarDayEntity(
    @ColumnInfo("assistant_id")
    val assistantId: String,
    val date: String,
    @ColumnInfo("entries_json")
    val entriesJson: String,
    val revision: Long,
)
