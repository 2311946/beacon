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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import me.rerere.rikkahub.data.repository.AutonomousActivityRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.time.Instant
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
    val activityFlow = remember(repository, assistantId) {
        repository.observeActivities(assistantId)
    }
    val activities = activityFlow.collectAsLazyPagingItems()
    val refreshState = activities.loadState.refresh

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
            if (activities.itemCount == 0) {
                when (refreshState) {
                    is LoadState.Loading -> ActivityListStatus(
                        message = "正在加载活动记录…",
                        loading = true,
                    )

                    is LoadState.Error -> ActivityListStatus(
                        message = "活动记录加载失败，请重试",
                        onRetry = { activities.retry() },
                    )

                    is LoadState.NotLoading -> ActivityListStatus(
                        message = "暂无活动记录\n角色自主活动后，会在这里留下记录",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (refreshState is LoadState.Loading) {
                        item(key = "refresh_loading") {
                            ActivityListStatus(message = "正在更新…", loading = true)
                        }
                    } else if (refreshState is LoadState.Error) {
                        item(key = "refresh_error") {
                            ActivityListStatus(
                                message = "更新失败，已保留现有记录",
                                onRetry = { activities.retry() },
                            )
                        }
                    }

                    items(
                        count = activities.itemCount,
                        key = activities.itemKey { it.id },
                        contentType = { "activity" },
                    ) { index ->
                        val activity = activities[index] ?: return@items
                        AutonomousActivityItem(
                            activity = activity,
                            modifier = Modifier.animateItem(),
                        )
                    }

                    when (activities.loadState.append) {
                        is LoadState.Loading -> item(key = "append_loading") {
                            ActivityListStatus(message = "正在加载更多…", loading = true)
                        }

                        is LoadState.Error -> item(key = "append_error") {
                            ActivityListStatus(
                                message = "更多记录加载失败",
                                onRetry = { activities.retry() },
                            )
                        }

                        is LoadState.NotLoading -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun AutonomousActivityItem(
    activity: AutonomousActivityEntity,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isContact = activity.decision == "send_message"
    val decisionLabel = when (activity.decision) {
        "send_message" -> "决定联系"
        "sleep" -> "暂不打扰"
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
