package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collectLatest
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon

private data class ChatPreviewResult(
    val conversationId: Uuid,
    val query: String,
    val rounds: List<ChatPreviewRound>,
    val filtered: List<ChatPreviewRound>,
)

/** 单聊与群聊共享地图；点击时用稳定节点 ID 解析当前原始索引。 */
@Composable
internal fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    chatListState: LazyListState,
    listState: LazyListState,
    settings: Settings,
    hazeState: HazeState,
    onJumpToMessage: (Int) -> Unit,
    groupMode: Boolean = false,
) {
    var searchQuery by rememberSaveable(conversation.id) { mutableStateOf("") }
    val query = searchQuery.trim()
    val currentConversation by rememberUpdatedState(conversation)
    val currentSettings by rememberUpdatedState(settings)
    // 清洗全文和搜索都在后台；流式生成只保留最新待处理快照。
    val result by produceState<ChatPreviewResult?>(
        initialValue = null,
        conversation.id,
        groupMode,
    ) {
        value = null
        snapshotFlow { Triple(currentConversation, currentSettings, searchQuery.trim()) }
            .conflate()
            .collectLatest { (snapshot, setting, search) ->
                value = withContext(Dispatchers.Default) {
                    val rounds = snapshot.messageNodes.toChatPreviewRounds(snapshot.chatRecordSenderNames(setting, groupMode))
                    ChatPreviewResult(snapshot.id, search, rounds,
                        if (search.isEmpty()) rounds else rounds.filter { it.matches(search) })
                }
            }
    }
    // 搜索切换时不把上一条查询的结果当作新结果；生成中仍可展示同会话的上一帧。
    val visibleResult = result?.takeIf { it.conversationId == conversation.id && it.query == query }
    val rounds = visibleResult?.rounds.orEmpty()
    val filtered = visibleResult?.filtered.orEmpty()
    val currentIndex = remember(conversation.id) { chatListState.firstVisibleItemIndex }
    val loaded = visibleResult != null
    var positionedQuery by remember(conversation.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded, query, conversation.id) {
        if (loaded && positionedQuery != query) {
            if (query.isEmpty()) {
                val roundIndex = rounds.indexOfFirst { currentIndex in it.firstIndex..it.lastIndex }
                listState.scrollToItem((roundIndex - 1).coerceAtLeast(0))
            } else {
                listState.scrollToItem(0)
            }
            positionedQuery = query
        }
    }
    val jumpToNode: (Uuid) -> Unit = { nodeId ->
        val latest = currentConversation
        if (latest.id == conversation.id) {
            val index = latest.messageNodes.chatPreviewIndexOf(nodeId)
            if (index >= 0) onJumpToMessage(index)
        }
    }
    // 搜索框独立于滚动列表，始终固定在顶栏下方。
    Column(
        modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search_messages)) },
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = "搜索消息") },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (query.isEmpty()) "${rounds.size}" else "${filtered.size} / ${rounds.size}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "清除搜索")
                        }
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).hazeSource(hazeState),
        ) {
            if (loaded && filtered.isEmpty()) {
                item(key = "PreviewEmpty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.search_page_no_results))
                    }
                }
            }
            items(filtered, key = { it.id }) { round ->
                ChatPreviewRoundCard(round, query, groupMode,
                    currentIndex in round.firstIndex..round.lastIndex, jumpToNode,
                    settings, conversation)
            }
        }
    }
}

@Composable
private fun ChatPreviewRoundCard(
    round: ChatPreviewRound,
    query: String,
    groupMode: Boolean,
    isCurrent: Boolean,
    onJumpToMessage: (Uuid) -> Unit,
    settings: Settings,
    conversation: Conversation,
) {
    val colors = MaterialTheme.colorScheme
    val assistants = remember(settings.assistants) { settings.assistants.associateBy { it.id } }
    val defaultAssistant = assistants[conversation.assistantId]
    fun sender(entry: ChatPreviewEntry): Assistant? = if (groupMode) {
        conversation.config?.speakerMap?.get(entry.message.id)?.let(assistants::get)
    } else defaultAssistant
    Surface(
        onClick = { onJumpToMessage(round.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        border = if (isCurrent) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) Text("当前阅读", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(
                    round.entries.first().message.createdAt.toString().replace('T', ' ').take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (round.favorite) Icon(HugeIcons.FavouriteCircle, "包含收藏消息", Modifier.size(16.dp), tint = colors.primary)
            }
            if (groupMode) {
                // 每条成员发言都有自己的署名与原始跳转位置，不用最后一个头像代表全轮。
                round.entries.forEach { entry ->
                    ChatPreviewEntryRow(entry, query, onJumpToMessage, sender(entry), settings)
                }
            } else {
                val first = round.entries.first()
                ChatPreviewEntryRow(first, query, onJumpToMessage, sender(first), settings)
                val replies = round.entries.drop(1)
                if (replies.isNotEmpty()) {
                    // 单聊工具多步仍合成一个摘要，全文搜索不会只检查最后一个节点。
                    val text = replies.map { it.text }.filter { it.isNotBlank() }.joinToString(" ")
                    val target = replies.firstOrNull { query.isNotEmpty() && it.text.contains(query, true) } ?: replies.first()
                    ChatPreviewEntryRow(target.copy(text = text,
                        message = target.message.copy(parts = replies.flatMap { it.message.parts })), query, onJumpToMessage, sender(target), settings)
                }
            }
        }
    }
}

@Composable
private fun ChatPreviewEntryRow(
    entry: ChatPreviewEntry,
    query: String,
    onJumpToMessage: (Uuid) -> Unit,
    assistant: Assistant?,
    settings: Settings,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "跳转到消息") { onJumpToMessage(entry.nodeId) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (entry.message.role) {
                MessageRole.USER -> UIAvatar(name = entry.senderName, value = settings.displaySetting.userAvatar,
                    modifier = Modifier.size(18.dp))
                MessageRole.ASSISTANT -> if (assistant != null) {
                    UIAvatar(name = entry.senderName, value = assistant.avatar, modifier = Modifier.size(18.dp))
                } else {
                    AutoAIIcon(name = entry.message.modelSnapshot?.modelId ?: "AI", modifier = Modifier.size(18.dp))
                }
                else -> Unit
            }
            Text(entry.senderName, style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
        val snippet = remember(entry.text, query) { chatPreviewSnippet(entry.text, query) }
        val highlightColor = colors.tertiaryContainer
        val highlightTextColor = colors.onTertiaryContainer
        val annotated = remember(snippet, query, highlightColor, highlightTextColor) {
            highlightedChatPreview(snippet, query, highlightColor, highlightTextColor)
        }
        if (snippet.isNotBlank()) {
            Text(
                annotated,
                style = if (entry.message.role == MessageRole.USER) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = if (entry.message.role == MessageRole.USER) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val parts = entry.message.parts
            val attachments = buildList {
                val images = parts.count { it is UIMessagePart.Image }
                val files = parts.count { it is UIMessagePart.Document || it is UIMessagePart.Audio || it is UIMessagePart.Video }
                val tools = parts.count { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }
                if (images > 0) add("图片 $images")
                if (files > 0) add("附件 $files")
                if (tools > 0) add("工具调用 $tools")
            }.joinToString(" · ").ifBlank { "[无正文]" }
            Text(attachments, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun highlightedChatPreview(text: String, query: String, background: Color, foreground: Color) = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
    } else {
        var start = 0
        var index = text.indexOf(query, ignoreCase = true)
        while (index >= 0) {
            append(text.substring(start, index))
            withStyle(SpanStyle(background = background, color = foreground, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
            index = text.indexOf(query, start, ignoreCase = true)
        }
        append(text.substring(start))
    }
}
