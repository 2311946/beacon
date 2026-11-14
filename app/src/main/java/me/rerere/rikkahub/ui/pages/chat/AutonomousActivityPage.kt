package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.repository.AutonomousActivityRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutonomousActivityPage(
    assistantId: Uuid,
    repository: AutonomousActivityRepository = koinInject(),
) {
    val zone = ZoneId.systemDefault()
    val dayFlow = remember(repository, assistantId, zone) { repository.observeDayCounts(assistantId) }
    val (days, retryDays) = key(repository, assistantId, zone) { observeActivityList(dayFlow) }
    // 只保留展开状态；每次进入页面默认全部收起，角色之间不共享。
    var expandedDays by remember(assistantId, zone) { mutableStateOf(emptySet<String>()) }
    val expandedRecords = days.items.filter { it.day in expandedDays }.associate { day ->
        val records = key(repository, assistantId, zone, day.day) {
            val flow = remember(repository, assistantId, zone, day.day) {
                val date = LocalDate.parse(day.day)
                repository.observeRange(
                    assistantId,
                    date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
            observeActivityList(flow)
        }
        day.day to records
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活动记录") },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (days.items.isEmpty()) {
                ActivityListStatus(
                    message = when {
                        days.loading -> "正在加载活动记录…"
                        days.error != null -> "活动记录加载失败，请重试"
                        else -> "暂无活动记录\n角色活动或状态更新后，会在这里留下记录"
                    },
                    loading = days.loading,
                    onRetry = if (days.error != null) retryDays else null,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (days.error != null) {
                        item(key = "days_error") {
                            ActivityListStatus(message = "更新失败，已保留现有记录", onRetry = retryDays)
                        }
                    }
                    days.items.forEach { day ->
                        val expanded = day.day in expandedDays
                        item(key = "day:${day.day}", contentType = "day") {
                            Surface(
                                onClick = {
                                    expandedDays = if (expanded) expandedDays - day.day else expandedDays + day.day
                                },
                                modifier = Modifier.fillMaxWidth().semantics {
                                    stateDescription = if (expanded) "已展开" else "已收起"
                                },
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = day.day,
                                        style = MaterialTheme.typography.titleMediumEmphasized,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${day.count} 条活动", style = MaterialTheme.typography.labelLarge)
                                    Icon(
                                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                        contentDescription = if (expanded) "收起当天记录" else "展开当天记录",
                                    )
                                }
                            }
                        }
                        expandedRecords[day.day]?.let { (records, retryRecords) ->
                            if (records.loading || records.error != null || records.items.isEmpty()) {
                                item(key = "status:${day.day}") {
                                    ActivityListStatus(
                                        message = when {
                                            records.loading -> "正在加载当天记录…"
                                            records.error != null -> "当天记录加载失败，请重试"
                                            else -> "当天暂无记录"
                                        },
                                        loading = records.loading,
                                        onRetry = if (records.error != null) retryRecords else null,
                                    )
                                }
                            }
                            items(records.items.asReversed(), key = { "activity:${it.id}" }, contentType = { "activity" }) { activity ->
                                AutonomousActivityItem(activity = activity, modifier = Modifier.animateItem())
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ActivityListState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = true,
    val error: Throwable? = null,
)

/** 列表首次加载、实时更新和重试共用；失败时保留上次记录。 */
@Composable
private fun <T> observeActivityList(flow: Flow<List<T>>): Pair<ActivityListState<T>, () -> Unit> {
    var retry by remember(flow) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by produceState(ActivityListState<T>(), flow, retry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = value.copy(loading = true, error = null)
            try {
                flow.collect { value = ActivityListState(items = it, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                value = value.copy(loading = false, error = e)
            }
        }
    }
    return state to { retry++ }
}

@Composable
private fun AutonomousActivityItem(
    activity: AutonomousActivityEntity,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isContact = activity.decision == "message_sent"
    val decisionLabel = when (activity.decision) {
        "send_message" -> "决定联系（旧记录，发送结果未知）"
        "message_sent" -> "已发送消息"
        "not_sent" -> "想联系，但未发送"
        "sleep" -> "暂不打扰"
        "status_chat" -> "聊天活动"
        "status_refresh" -> "日常活动"
        "status_manual" -> "手动设置的活动"
        else -> activity.decision.ifBlank { "未记录决策" }
    }
    val timestamp = remember(activity.createdAt) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(activity.createdAt).atZone(ZoneId.systemDefault()))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(colors.outlineVariant),
            )
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .size(12.dp)
                    .background(if (isContact) colors.primary else colors.outline, CircleShape),
            )
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = activity.assistantName.ifBlank { "未命名角色" },
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = colors.onSurface,
                    )
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    text = activity.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Surface(
                    shape = CircleShape,
                    color = if (isContact) colors.primaryContainer else colors.secondaryContainer,
                    contentColor = if (isContact) colors.onPrimaryContainer else colors.onSecondaryContainer,
                ) {
                    Text(
                        text = decisionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityListStatus(
    message: String,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
