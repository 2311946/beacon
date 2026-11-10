package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AssistantCalendarDayEntity

@Dao
interface AssistantCalendarDAO {
    @Query("SELECT * FROM assistant_calendar_day WHERE assistant_id = :id AND date = :date")
    suspend fun getDay(id: String, date: String): AssistantCalendarDayEntity?

    @Query(
        "SELECT * FROM assistant_calendar_day WHERE assistant_id = :id " +
            "AND date != 'template' AND date >= :start AND date < :endExclusive ORDER BY date ASC"
    )
    fun observeRange(id: String, start: String, endExclusive: String): Flow<List<AssistantCalendarDayEntity>>

    @Upsert
    suspend fun upsert(entity: AssistantCalendarDayEntity)
}
