package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AssistantCalendarGenerator
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.model.AssistantCalendarEntry
import me.rerere.rikkahub.data.repository.AssistantCalendarRepository
import me.rerere.rikkahub.data.repository.AutonomousActivityRepository
import kotlin.uuid.Uuid

data class CalendarDayView(
    val date: LocalDate,
    val entries: List<AssistantCalendarEntry>,
    val revision: Long?,
    val activities: List<AutonomousActivityEntity>,
)

data class AssistantCalendarState(
    val selected: LocalDate = LocalDate.now(),
    val week: Boolean = false,
    val days: List<CalendarDayView> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

class AssistantCalendarVM(
    private val assistantId: Uuid,
    private val repository: AssistantCalendarRepository,
    private val activities: AutonomousActivityRepository,
    private val generator: AssistantCalendarGenerator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AssistantCalendarState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null
    private var generation: Job? = null
    private var generationToken: Any? = null

    init { select(LocalDate.now(), false) }

    fun select(date: LocalDate, week: Boolean) {
        observer?.cancel()
        generation?.cancel()
        val first = if (week) date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else date
        val dates = List(if (week) 7 else 1) { first.plusDays(it.toLong()) }
        val zone = ZoneId.systemDefault()
        mutableState.value = mutableState.value.copy(selected = date, week = week, days = emptyList(), loading = true, error = null)
        observer = viewModelScope.launch {
            try {
                combine(
                    repository.observeDays(assistantId, first, dates.last().plusDays(1)),
                    activities.observeRange(
                        assistantId,
                        first.atStartOfDay(zone).toInstant().toEpochMilli(),
                        dates.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    ),
                ) { plans, actual ->
                    dates.map { day ->
                        val plan = plans.firstOrNull { it.date == day.toString() }
                        CalendarDayView(
                            day, plan?.let(repository::decode).orEmpty(), plan?.revision,
                            actual.filter { java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == day },
                        )
                    }
                }.collect { days -> mutableState.value = mutableState.value.copy(days = days, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = "日历读取失败：${e.message}")
            }
        }
        generate(dates, false)
    }

    fun retry() = select(state.value.selected, state.value.week)

    fun regenerate(date: LocalDate) {
        if (!state.value.busy) generate(listOf(date), true)
    }

    private fun generate(dates: List<LocalDate>, regenerate: Boolean) {
        val token = Any()
        generationToken = token
        generation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                dates.forEach { date ->
                    if (regenerate) generator.regenerateDay(assistantId, date) else generator.ensureDay(assistantId, date)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(error = "日程生成失败，原计划已保留：${e.message}")
            } finally {
                if (generationToken === token) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun save(date: LocalDate, entries: List<AssistantCalendarEntry>, revision: Long?, updateTemplate: Boolean, onSaved: () -> Unit) {
        if (state.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                check(repository.commitGeneratedDay(assistantId, date, entries, revision, false, updateTemplate)) {
                    "日程已更新，请关闭编辑框后重新打开"
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(error = "保存失败，原计划已保留：${e.message}")
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}
