package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.AssistantCalendarDayEntity
import me.rerere.rikkahub.data.model.AssistantCalendarEntry
import me.rerere.rikkahub.data.model.validateCalendarEntries
import me.rerere.rikkahub.utils.JsonInstant
import java.time.LocalDate
import kotlin.uuid.Uuid

private const val TEMPLATE_DATE = "template"

class AssistantCalendarRepository(
    private val database: AppDatabase,
) {
    suspend fun getDay(assistantId: Uuid, date: LocalDate): AssistantCalendarDayEntity? =
        database.assistantCalendarDao().getDay(assistantId.toString(), date.toString())

    suspend fun getTemplate(assistantId: Uuid): AssistantCalendarDayEntity? =
        database.assistantCalendarDao().getDay(assistantId.toString(), TEMPLATE_DATE)

    fun observeDays(
        assistantId: Uuid,
        start: LocalDate,
        endExclusive: LocalDate,
    ): Flow<List<AssistantCalendarDayEntity>> = database.assistantCalendarDao().observeRange(
        id = assistantId.toString(),
        start = start.toString(),
        endExclusive = endExclusive.toString(),
    )

    fun decode(day: AssistantCalendarDayEntity): List<AssistantCalendarEntry> =
        validateCalendarEntries(JsonInstant.decodeFromString<List<AssistantCalendarEntry>>(day.entriesJson))

    suspend fun saveDay(
        assistantId: Uuid,
        date: LocalDate,
        entries: List<AssistantCalendarEntry>,
    ) {
        val entriesJson = JsonInstant.encodeToString(validateCalendarEntries(entries))
        database.withTransaction {
            val dao = database.assistantCalendarDao()
            val id = assistantId.toString()
            val dayDate = date.toString()
            val current = dao.getDay(id, dayDate)
            dao.upsert(
                AssistantCalendarDayEntity(
                    assistantId = id,
                    date = dayDate,
                    entriesJson = entriesJson,
                    revision = maxOf(System.currentTimeMillis(), (current?.revision ?: 0L) + 1L),
                )
            )
        }
    }

    suspend fun commitGeneratedDay(
        assistantId: Uuid,
        date: LocalDate,
        entries: List<AssistantCalendarEntry>,
        expectedRevision: Long?,
        createTemplate: Boolean,
        updateTemplate: Boolean = false,
    ): Boolean = database.withTransaction {
        val dao = database.assistantCalendarDao()
        val id = assistantId.toString()
        val dayDate = date.toString()
        val current = dao.getDay(id, dayDate)
        if (current?.revision != expectedRevision) return@withTransaction false

        val day = AssistantCalendarDayEntity(
            assistantId = id,
            date = dayDate,
            entriesJson = JsonInstant.encodeToString(validateCalendarEntries(entries)),
            revision = maxOf(System.currentTimeMillis(), (current?.revision ?: 0L) + 1L),
        )
        dao.upsert(day)
        if (updateTemplate || (createTemplate && dao.getDay(id, TEMPLATE_DATE) == null)) {
            dao.upsert(day.copy(date = TEMPLATE_DATE))
        }
        true
    }

    suspend fun materializeDay(id: Uuid, date: LocalDate): Boolean = database.withTransaction {
        val dao = database.assistantCalendarDao()
        val assistantId = id.toString()
        val dayDate = date.toString()
        if (dao.getDay(assistantId, dayDate) != null) return@withTransaction true
        val template = dao.getDay(assistantId, TEMPLATE_DATE) ?: return@withTransaction false
        dao.upsert(
            template.copy(
                date = dayDate,
                revision = maxOf(System.currentTimeMillis(), template.revision + 1L),
            )
        )
        true
    }
}
