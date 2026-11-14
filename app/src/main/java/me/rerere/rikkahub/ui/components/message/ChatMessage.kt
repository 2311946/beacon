package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import me.rerere.rikkahub.ui.theme.LocalChatTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.StickerStore
import me.rerere.rikkahub.data.model.StickerReplyResolver
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.isVoiceCallMessage
import me.rerere.rikkahub.data.model.voiceCallPreview
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.SelectableContent
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.webview.WebViewContentCache
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    showMessageHeader: Boolean = true,
    showVoiceCallDetails: Boolean = false,
    onGenerateImage: ((UIMessage) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    val wakeupTaskMetadata = (message.parts.singleOrNull() as? UIMessagePart.Text)?.metadata
    if (message.role == MessageRole.USER &&
        (wakeupTaskMetadata?.get("beacon_event") as? JsonPrimitive)?.content == "autonomous_task"
    ) {
        val assistantName = (wakeupTaskMetadata?.get("assistant_name") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: assistant?.name?.takeIf { it.isNotBlank() } ?: "角色"
        val task = (wakeupTaskMetadata?.get("task_description") as? JsonPrimitive)?.content.orEmpty()
        val status = (wakeupTaskMetadata?.get("task_status") as? JsonPrimitive)?.content ?: "running"
        var expanded by androidx.compose.runtime.saveable.rememberSaveable(message.id.toString()) {
            mutableStateOf(false)
        }
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = me.rerere.rikkahub.data.model.wakeupTaskLabel(assistantName, task, status) + if (expanded) " ▴" else " ▾",
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(visible = expanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("自主唤醒任务", style = MaterialTheme.typography.labelMedium)
                            Text("任务：$task", style = MaterialTheme.typography.bodySmall)
                            Text("状态：$status", style = MaterialTheme.typography.bodySmall)
                            val details = (wakeupTaskMetadata?.get("task_result") as? JsonPrimitive)?.content
                            if (!details.isNullOrBlank()) Text("结果：$details", style = MaterialTheme.typography.bodySmall)
                            val decision = (wakeupTaskMetadata?.get("decision_content") as? JsonPrimitive)?.content
                            if (!decision.isNullOrBlank()) Text("决策原文：$decision", style = MaterialTheme.typography.bodySmall)
                            val prompt = (message.parts.singleOrNull() as? UIMessagePart.Text)?.text.orEmpty()
                            if (prompt.isNotBlank()) Text(prompt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        return
    }
    val wakeupMetadata = (message.parts.singleOrNull() as? UIMessagePart.Text)?.metadata
    if (message.role == MessageRole.USER &&
        (wakeupMetadata?.get("beacon_event") as? JsonPrimitive)?.content == "autonomous_wakeup"
    ) {
        val assistantName = (wakeupMetadata?.get("assistant_name") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: assistant?.name?.takeIf { it.isNotBlank() } ?: "角色"
        val reason = (wakeupMetadata?.get("reason") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: "想和你聊聊"
        var expanded by androidx.compose.runtime.saveable.rememberSaveable(message.id.toString()) {
            mutableStateOf(false)
        }
        val fullReason = (wakeupMetadata?.get("full_reason") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
        val decisionContent = (wakeupMetadata?.get("decision_content") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
        val savedPrompt = (message.parts.singleOrNull() as? UIMessagePart.Text)?.text.orEmpty()
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "💤 ${assistantName}主动找你了 · $reason ${if (expanded) "▴" else "▾"}",
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "收起唤醒决策" else "展开唤醒决策") {
                        expanded = !expanded
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(visible = expanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("唤醒决策", style = MaterialTheme.typography.labelMedium)
                            if (fullReason != null) {
                                Text("联系理由：$fullReason", style = MaterialTheme.typography.bodySmall)
                            }
                            if (decisionContent != null) {
                                Text("模型返回的决策：", style = MaterialTheme.typography.labelSmall)
                                Text(decisionContent, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    "此历史消息未保存完整决策原文，以下为当时保留在上下文中的内容。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (savedPrompt.isNotBlank()) {
                                Text("发给角色的内部提示：", style = MaterialTheme.typography.labelSmall)
                                Text(savedPrompt, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        return
    }
    if (message.isContextCheckpoint) {
        ChatMessageContextCheckpoint(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
        )
        return
    }
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isVoiceCall = message.isVoiceCallMessage()
    var voiceCallExpanded by androidx.compose.runtime.saveable.rememberSaveable(message.id.toString()) {
        mutableStateOf(false)
    }
    // 工具需要审批时保持可见，不能让折叠把生成链卡住。
    val needsApproval = message.getTools().any { it.isPending }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isVoiceCall && !showVoiceCallDetails) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = settings.bubbleOpacity),
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClickLabel = if (voiceCallExpanded) "收起通话消息" else "展开通话消息") {
                            voiceCallExpanded = !voiceCallExpanded
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("📞", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = message.voiceCallPreview(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (voiceCallExpanded || needsApproval) "▴" else "▾")
                }
            }
        }
        if (!isVoiceCall || voiceCallExpanded || needsApproval || showVoiceCallDetails) {
            if (showMessageHeader && !message.parts.isEmptyUIMessage()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ChatMessageAssistantAvatar(
                        message = message,
                        model = model,
                        assistant = assistant,
                        loading = loading,
                        modifier = Modifier.weight(1f)
                    )
                    ChatMessageUserAvatar(
                        message = message,
                        avatar = settings.userAvatar,
                        nickname = settings.userNickname,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            CompositionLocalProvider(
                LocalTextStyle provides textStyle,
                LocalWorkspaceId provides assistant?.workspaceId?.toString(),
            ) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = message.parts,
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                )

                message.translation?.let { translation ->
                    CollapsibleTranslationText(
                        content = translation,
                        onClickCitation = {}
                    )
                }
            }

            val showActions = if (lastMessage) {
                !loading
            } else {
                message.parts.isEmptyUIMessage().not()
            }

            val motionScheme = MaterialTheme.motionScheme
            AnimatedVisibility(
                visible = showActions,
                enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                    fadeIn(motionScheme.defaultEffectsSpec()),
                exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                    fadeOut(motionScheme.fastEffectsSpec())
            ) {
                Column(modifier = Modifier.animateContentSize()) {
                    ChatMessageActionButtons(
                        message = message,
                        onRegenerate = onRegenerate,
                        node = node,
                        onUpdate = onUpdate,
                        onOpenActionSheet = { showActionsSheet = true },
                        onTranslate = onTranslate,
                        onClearTranslation = onClearTranslation,
                        onGenerateImage = onGenerateImage,
                    )
                }
            }

            EditedFilesList(
                parts = message.parts,
                assistant = assistant,
            )

            ProvideTextStyle(textStyle) {
                ChatMessageNerdLine(message = message)
            }
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            },
            onGenerateImage = onGenerateImage,
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

// 思考链折叠时默认保留的尾部步骤数
private const val COLLAPSED_VISIBLE_STEPS = 2

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val stickerStore = remember(context) { StickerStore.get(context) }
    val stickerGroups by stickerStore.groups.collectAsStateWithLifecycle()
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)
    val chatTheme = LocalChatTheme.current
    val chatImageMarker = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull { text ->
        (text.metadata?.get("beacon_event") as? JsonPrimitive)?.content == me.rerere.rikkahub.data.model.ChatImageGeneration.EVENT
    }
    val imageStatus = (chatImageMarker?.metadata?.get("status") as? JsonPrimitive)?.content
    if (chatImageMarker != null && imageStatus != "SUCCEEDED") {
        val error = (chatImageMarker.metadata?.get("error") as? JsonPrimitive)?.content
        val active = imageStatus != "FAILED" && imageStatus != "CANCELLED" && error == null
        androidx.compose.material3.ListItem(
            headlineContent = {
                Text(
                    when {
                        imageStatus == "CANCELLED" -> "图片生成已取消，可长按消息重试"
                        error != null -> "图片生成失败：$error（长按消息可重试）"
                        imageStatus == "SUBMITTING" -> "正在提交生图请求…"
                        else -> "正在生成图片…"
                    },
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            },
            leadingContent = {
                if (active) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp))
            },
        )
    }

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 等待审批的工具不能折叠起来，否则用户看不到审批按钮，这一轮会一直卡在等待审批
                    val firstPendingIndex = block.steps.indexOfFirst { it is ThinkingStep.ToolStep && it.tool.isPending }
                    val collapsedVisibleCount = if (firstPendingIndex == -1) {
                        COLLAPSED_VISIBLE_STEPS
                    } else {
                        maxOf(COLLAPSED_VISIBLE_STEPS, block.steps.size - firstPendingIndex)
                    }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedVisibleCount = collapsedVisibleCount,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        assistant = assistant,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ChartBlock -> key(block.index) {
                val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        // 只有正在生成的最后一段文字可能停在没写完的语法上
                        val streamingText = loading && block.index == parts.lastIndex
                        val assistantDisplayText = remember(part.text, assistant, stickerGroups, streamingText, role) {
                            if (role == MessageRole.USER) part.text else {
                                val visualText = part.text.replaceRegexes(
                                    assistant = assistant,
                                    scope = AssistantAffectScope.ASSISTANT,
                                    visual = true,
                                )
                                if (role == MessageRole.ASSISTANT) {
                                    StickerReplyResolver.renderBareImageUrls(
                                        StickerReplyResolver.resolve(
                                            me.rerere.rikkahub.data.ai.foldChatImageDescriptions(visualText, streamingText),
                                            stickerGroups,
                                            streamingText,
                                        ),
                                        streaming = streamingText,
                                    )
                                } else visualText
                            }
                        }
                        val textContent = @Composable {
                            if (role == MessageRole.ASSISTANT &&
                                (part.metadata?.get("beacon_event") as? JsonPrimitive)?.content == "empty_reply"
                            ) {
                                Text(
                                    "回复为空，请重试",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            } else if (part.text.isBlank() && me.rerere.rikkahub.data.model.ChatImageGeneration.isMarker(part)) {
                                // 生图状态单独显示，不把空元数据标记渲染成用户空气泡。
                            } else if (role == MessageRole.ASSISTANT && assistantDisplayText.isBlank()) {
                                // 流式空白片段不创建空气泡，等待共享生成链重试。
                            } else if (role == MessageRole.USER) {
                                ChatMessageBubble(
                                    role = role,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    onClick = { onUserMessageClick?.invoke() },
                                    // 用户气泡不占满整行，和助手的回复错开
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .animateContentSize(),
                                ) {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.USER,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation
                                    )
                                }
                            } else {
                                if (chatTheme.forceAssistantBubble || settings.displaySetting.showAssistantBubble) {
                                    ChatMessageBubble(
                                        role = role,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                                        modifier = Modifier.animateContentSize(),
                                    ) {
                                        MarkdownBlock(
                                            content = assistantDisplayText,
                                            novelStyle = role == MessageRole.ASSISTANT && settings.displaySetting.enableNovelMessageStyle,
                                            streaming = streamingText,
                                            onClickCitation = handleClickCitation,
                                        )
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = assistantDisplayText,
                                        novelStyle = role == MessageRole.ASSISTANT && settings.displaySetting.enableNovelMessageStyle,
                                        streaming = streamingText,
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // Markdown 由 BetterText 渲染，要用它自己的选择容器；生成中也可以选择
                        SelectableContent {
                            textContent()
                        }
                    }

                    is UIMessagePart.Video -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.Video01,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Audio -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.MusicNote03,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else if (me.rerere.rikkahub.data.datastore.isStickerImage(part.url, null, stickerGroups)) {
                            me.rerere.rikkahub.ui.components.richtext.ChatStickerImage(
                                model = part.url,
                                contentDescription = "表情",
                                modifier = Modifier.clip(MaterialTheme.shapes.large),
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        ChatMessageFileChip(
                            text = part.fileName,
                            onClick = { context.openLocalFile(part.url) },
                            icon = {
                                when (part.mime) {
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.docx),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    "application/pdf" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.pdf),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    else -> {
                                        Icon(
                                            imageVector = HugeIcons.File02,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                        )
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        ChatMessageCitations(annotations = annotations)
    }
}

private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubblePressedCorner = 12.dp

/**
 * 消息气泡：大圆角，靠头像一侧的顶角收紧；可点击时按下其余圆角也跟着收紧
 */
@Composable
private fun ChatMessageBubble(
    role: MessageRole,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    val chatTheme = LocalChatTheme.current
    val corner = chatTheme.bubbleCorner ?: lerp(BubbleCorner, BubblePressedCorner, pressProgress)
    val shape = if (chatTheme.bubbleCorner != null) {
        RoundedCornerShape(corner)
    } else if (role == MessageRole.USER) {
        RoundedCornerShape(topStart = corner, topEnd = BubbleTailCorner, bottomEnd = corner, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = BubbleTailCorner, topEnd = corner, bottomEnd = corner, bottomStart = corner)
    }
    val contentColor = chatTheme.textColor ?: contentColorFor(color)
    val bubbleBackground = when {
        role == MessageRole.USER && chatTheme.userBubbleColors.isNotEmpty() ->
            Modifier.background(Brush.horizontalGradient(chatTheme.userBubbleColors))
        role == MessageRole.ASSISTANT && chatTheme.assistantBubbleColor != null ->
            Modifier.background(chatTheme.assistantBubbleColor)
        else -> Modifier.background(color)
    }
    val bubbleBorder = if (role == MessageRole.ASSISTANT && chatTheme.assistantBubbleBorder != null) {
        Modifier.border(1.dp, chatTheme.assistantBubbleBorder, shape)
    } else Modifier
    Box(
        modifier = modifier
            .clipDrawing(shape)
            .then(bubbleBackground)
            .then(bubbleBorder)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                } else {
                    Modifier
                },
            )
            // MarkdownBlock 自带 4dp 的水平内边距
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * 只裁剪绘制内容，不像 [clip] 那样把形状设成图层轮廓。
 *
 * 四个角半径不同时图层轮廓是 Path，Compose 判断触摸点是否落在轮廓内时用 Path 求交，
 * 坐标超过 8192px 后 float 精度不够，求交结果为空，长消息下半部分（例如展开的长代码块）就收不到触摸了。
 */
private fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent {
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}

@Composable
private fun ChatMessageCitations(annotations: List<UIMessageAnnotation>) {
    var expand by remember { mutableStateOf(false) }
    val urls = remember(annotations) {
        annotations.map { annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> annotation.url
            }
        }
    }
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatMessageFileChip(
            text = stringResource(R.string.citations_count, annotations.size),
            onClick = { expand = !expand },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { FaviconRow(urls = urls, size = 18.dp) },
        )
        if (expand) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
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
