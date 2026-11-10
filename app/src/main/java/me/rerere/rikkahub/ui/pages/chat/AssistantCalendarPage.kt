package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import me.rerere.rikkahub.data.model.AssistantCalendarEntry
import me.rerere.rikkahub.data.model.calendarTimeLabel
import me.rerere.rikkahub.data.model.calendarPlanIndex
import me.rerere.rikkahub.data.model.parseCalendarTime
import me.rerere.rikkahub.data.model.validateCalendarEntries
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

private data class CalendarEdit(val day: CalendarDayView, val index: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantCalendarPage(
    assistantId: Uuid,
    vm: AssistantCalendarVM = koinViewModel(key = "calendar:$assistantId", parameters = { parametersOf(assistantId) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var regenerate by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<CalendarEdit?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("角色日历") }, navigationIcon = { BackButton() })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.week, enabled = !state.busy, onClick = { vm.select(state.selected, false) }, label = { Text("日") })
                    FilterChip(selected = state.week, enabled = !state.busy, onClick = { vm.select(state.selected, true) }, label = { Text("周") })
                }
                TextButton(enabled = !state.busy, onClick = { vm.select(LocalDate.now(), state.week) }) { Text("今天") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !state.busy, onClick = { vm.select(state.selected.minusDays(if (state.week) 7L else 1L), state.week) }) { Text("上一${if (state.week) "周" else "天"}") }
                Text(state.selected.toString(), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = !state.busy, onClick = { vm.select(state.selected.plusDays(if (state.week) 7L else 1L), state.week) }) { Text("下一${if (state.week) "周" else "天"}") }
            }
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "实线为计划，虚线为实际活动。点击计划编辑，长按日期重新生成。实际记录仅按时间关联，不代表计划已完成。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { message ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !state.busy, onClick = vm::retry) { Text("重新加载 / 重试") }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.days.forEach { day ->
                    item(key = "date:${day.date}") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${day.date}  ${day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.CHINA)}",
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                modifier = Modifier.weight(1f).combinedClickable(
                                    onClick = {}, onLongClick = { if (!state.busy) regenerate = day.date },
                                    onLongClickLabel = "重新生成当日日程",
                                ).padding(vertical = 14.dp),
                            )
                            TextButton(enabled = !state.busy, onClick = { editing = CalendarEdit(day, null) }) { Text("添加") }
                        }
                    }
                    if (day.entries.isEmpty()) item(key = "empty:${day.date}") {
                        Text(
                            if (day.revision == null) "尚无计划，可等待生成或手动添加。" else "当天没有计划，可手动添加或长按日期重新生成。",
                            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val zone = ZoneId.systemDefault()
                    val activityMinutes = day.activities.associate { activity ->
                        val time = Instant.ofEpochMilli(activity.createdAt).atZone(zone)
                        activity.id to (time.hour * 60 + time.minute)
                    }
                    val assigned = day.activities.groupBy { activity ->
                        val minute = activityMinutes.getValue(activity.id)
                        calendarPlanIndex(day.entries, minute)
                    }
                    (0..23).forEach { hour ->
                        val plans = day.entries.withIndex().filter { it.value.startMinute / 60 == hour }
                        val unmatched = assigned[-1].orEmpty().filter { activityMinutes.getValue(it.id) / 60 == hour }
                        if (!state.week || plans.isNotEmpty() || unmatched.isNotEmpty()) {
                            item(key = "${day.date}:$hour") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(calendarTimeLabel(hour * 60), modifier = Modifier.width(48.dp).padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        plans.forEach { (index, plan) ->
                                            CalendarCard(
                                                label = "计划 ${calendarTimeLabel(plan.startMinute)}–${calendarTimeLabel(plan.endMinute)}",
                                                content = plan.content,
                                                actual = false,
                                                onClick = { if (!state.busy) editing = CalendarEdit(day, index) },
                                            )
                                            assigned[index].orEmpty().forEach { activity ->
                                                CalendarCard("实际 ${calendarTimeLabel(activityMinutes.getValue(activity.id))}", activity.content, true)
                                            }
                                        }
                                        unmatched.forEach { activity ->
                                            CalendarCard("实际 ${calendarTimeLabel(activityMinutes.getValue(activity.id))} · 无对应计划", activity.content, true)
                                        }
                                        if (plans.isEmpty() && unmatched.isEmpty() && !state.week) {
                                            Text("—", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    regenerate?.let { date ->
        AlertDialog(
            onDismissRequest = { regenerate = null },
            title = { Text("重新生成 $date 的日程？") },
            text = { Text("成功后替换当天的全部计划（包括手动修改），不会删除实际活动或修改其他日期。生成失败保留原计划。") },
            confirmButton = { TextButton(onClick = { regenerate = null; vm.regenerate(date) }) { Text("重新生成") } },
            dismissButton = { TextButton(onClick = { regenerate = null }) { Text("取消") } },
        )
    }
    editing?.let { edit ->
        CalendarEditDialog(
            edit = edit,
            busy = state.busy,
            saveError = state.error,
            onDismiss = { if (!state.busy) editing = null },
            onSave = { entries, updateTemplate ->
                vm.save(edit.day.date, entries, edit.day.revision, updateTemplate) { editing = null }
            },
        )
    }
}

@Composable
private fun CalendarCard(label: String, content: String, actual: Boolean, onClick: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (actual) colors.tertiary else colors.primary
    val dashed = if (actual) Modifier.drawWithContent {
        drawContent()
        val inset = 1.dp.toPx()
        drawRoundRect(
            color = borderColor, topLeft = Offset(inset, inset),
            size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(20.dp.toPx()),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    } else Modifier
    Surface(
        modifier = Modifier.fillMaxWidth().then(dashed),
        shape = shape,
        color = if (actual) colors.surfaceContainer else colors.primaryContainer,
        border = if (actual) null else BorderStroke(1.dp, borderColor),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (actual) colors.tertiary else colors.onPrimaryContainer)
            Text(content, style = MaterialTheme.typography.bodyMedium, color = if (actual) colors.onSurface else colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun CalendarEditDialog(edit: CalendarEdit, busy: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (List<AssistantCalendarEntry>, Boolean) -> Unit) {
    val initial = edit.index?.let { edit.day.entries[it] }
    var start by remember(edit) { mutableStateOf(calendarTimeLabel(initial?.startMinute ?: 9 * 60)) }
    var end by remember(edit) { mutableStateOf(calendarTimeLabel(initial?.endMinute ?: 10 * 60)) }
    var content by remember(edit) { mutableStateOf(initial?.content.orEmpty()) }
    var error by remember(edit) { mutableStateOf<String?>(null) }
    var updateTemplate by remember(edit) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加计划 · ${edit.day.date}" else "编辑计划 · ${edit.day.date}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardGroup {
                    formItem {
                        OutlinedTextField(value = start, onValueChange = { start = it }, enabled = !busy, label = { Text("开始 HH:mm") }, singleLine = true)
                    }
                    formItem {
                        OutlinedTextField(value = end, onValueChange = { end = it }, enabled = !busy, label = { Text("结束 HH:mm（可 24:00）") }, singleLine = true)
                    }
                    formItem {
                        OutlinedTextField(value = content, onValueChange = { content = it }, enabled = !busy, label = { Text("计划内容") }, minLines = 2, maxLines = 4)
                    }
                }
                Row {
                    Checkbox(checked = updateTemplate, onCheckedChange = { updateTemplate = it }, enabled = !busy)
                    Text("同时将当天完整计划设为日常模板（仅用于尚未创建的日期）", style = MaterialTheme.typography.bodySmall)
                }
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initial != null) TextButton(enabled = !busy, onClick = {
                    onSave(edit.day.entries.filterIndexed { index, _ -> index != edit.index }, updateTemplate)
                }) { Text("删除此计划", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    val entry = AssistantCalendarEntry(parseCalendarTime(start.trim()), parseCalendarTime(end.trim(), true), content)
                    val entries = edit.day.entries.toMutableList()
                    if (edit.index == null) entries.add(entry) else entries[edit.index] = entry
                    onSave(validateCalendarEntries(entries), updateTemplate)
                } catch (e: IllegalArgumentException) { error = e.message }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
