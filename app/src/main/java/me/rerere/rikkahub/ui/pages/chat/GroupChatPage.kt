package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ui.components.ListSelectableItem
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupSummaryStage
import me.rerere.rikkahub.data.model.validGroupSummaryStages
import me.rerere.rikkahub.data.model.voiceCallRanges
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.UsageBudgetBanner
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.ui.theme.ChatFontProvider
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

// TODO(i18n): 群聊专属文案后续统一提到 strings.xml。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatPage(
    groupId: String,
    vm: GroupChatVM = koinViewModel(parameters = { parametersOf(groupId) }),
) {
    val groupChat by vm.groupChat.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val memberActivities by vm.memberActivities.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val isSummarizing by vm.isSummarizing.collectAsStateWithLifecycle()
    val awaitingManualSpeaker by vm.awaitingManualSpeaker.collectAsStateWithLifecycle()
    val currentSpeakerId by vm.currentSpeakerId.collectAsStateWithLifecycle()
    val replyMessageId by vm.replyMessageId.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val conversationLoadError by vm.conversationError.collectAsStateWithLifecycle()

    val inputState = vm.inputState
    val inputAssistant = vm.members().firstOrNull()
    val chatModel = settings.findModelById(groupChat?.chatModelId)
        ?: settings.findModelById(inputAssistant?.chatModelId, settings.chatModelId)
    val groupName = groupChat?.name?.ifBlank { "未命名群聊" } ?: "群聊"
    val listState = rememberLazyListState()
    val previewListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    var previewMode by rememberSaveable(groupId) { mutableStateOf(false) }
    var showRecordMenu by remember(groupId) { mutableStateOf(false) }
    val selectionState = remember(groupId) { ChatSelectionState() }
    var exportConversation by remember(groupId) { mutableStateOf<Conversation?>(null) }
    var exportMessages by remember(groupId) { mutableStateOf<List<UIMessage>>(emptyList()) }
    var exportNames by remember(groupId) { mutableStateOf<Map<Uuid, String>>(emptyMap()) }
    var exportTitle by remember(groupId) { mutableStateOf("") }
    val previewProgress = rememberPreviewSwipeProgress(previewMode, groupId)
    val currentPreviewMode by rememberUpdatedState(previewMode)
    val currentSelecting by rememberUpdatedState(selectionState.selecting)
    val overviewVisible by rememberUpdatedState(previewProgress.value > 0f)
    BackHandler(previewMode && !selectionState.selecting) { previewMode = false }
    LaunchedEffect(groupId) {
        selectionState.cancel()
        exportConversation = null
    }
    val hazeState = rememberHazeState()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val nodes = conversation?.messageNodes.orEmpty()
    val currentNodes by rememberUpdatedState(nodes)
    val currentExportConversation by rememberUpdatedState(exportConversation)
    val callRanges = remember(nodes) { nodes.voiceCallRanges() }
    val expandedCalls = remember(groupId) { mutableStateMapOf<Uuid, Boolean>() }
    val pendingCallRanges = remember(nodes, callRanges) {
        callRanges.values.distinct().filter { range ->
            range.any { nodes[it].currentMessage.getTools().any { tool -> tool.isPending } }
        }.map { it.first }.toSet()
    }
    // 聊天流只提示当前有效总结；归档和失效内容移至群设置的历史总结。
    val summaryStages = remember(conversation) { conversation?.validGroupSummaryStages().orEmpty() }
    val summaryKeepRecentMessages = (groupChat?.resolved(settings.groupChatDefaults)
        ?: settings.groupChatDefaults).summaryKeepRecentMessages.coerceIn(0, 50)
    val canSummarize = groupChat != null && conversation != null && nodes.isNotEmpty() &&
        !isGenerating && !isSummarizing && !awaitingManualSpeaker
    // 消息行显式依赖页面订阅的角色卡和发言映射，头像修改后无需重新进入群聊。
    val assistantsById = remember(settings.assistants) { settings.assistants.associateBy { it.id } }
    val speakerMap = conversation?.config?.speakerMap.orEmpty()
    val generatingState by rememberUpdatedState(isGenerating)
    val lastContentIndex by rememberUpdatedState(
        nodes.size + summaryStages.size +
            (if (isGenerating || awaitingManualSpeaker) 1 else 0)
    )
    var showSummaryDialog by rememberSaveable(groupId) { mutableStateOf(false) }
    var showAttachments by remember(groupId) { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = settings,
        onAttachmentAdded = { showAttachments = false },
    )

    LaunchedEffect(nodes) {
        selectionState.selectedItems.retainAll(nodes.map { it.id }.toSet())
    }
    LaunchedEffect(groupId, nodes.size, summaryStages.size, isSummarizing) {
        if (!overviewVisible && !selectionState.selecting && exportConversation == null && (nodes.isNotEmpty() || summaryStages.isNotEmpty())) {
            listState.requestScrollToItem(lastContentIndex)
        }
    }
    ImeLazyListAutoScroller(lazyListState = listState,
        enabled = !overviewVisible && !selectionState.selecting && exportConversation == null)

    LaunchedEffect(awaitingManualSpeaker) {
        if (awaitingManualSpeaker) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            showAttachments = false
        }
    }

    // 群聊消息头由侧边头像行负责；不更改单聊全局显示设置。
    val groupSettings = remember(settings) {
        settings.copy(
            displaySetting = settings.displaySetting.copy(
                showUserAvatar = false,
                showModelName = true,
            )
        )
    }

    fun sendInput(answer: Boolean) {
        if (inputState.isEmpty()) return
        if (chatModel == null) {
            toaster.show("请先选择模型", type = ToastType.Error)
            return
        }
        vm.sendInput(answer = answer)
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        AssistantBackground(setting = settings, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = me.rerere.rikkahub.ui.theme.LocalChatTheme.current.topBarColor ?: Color.Transparent,
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            UIAvatar(
                                name = groupName,
                                value = groupChat?.avatar ?: Avatar.Dummy,
                                modifier = Modifier.size(32.dp),
                                onClick = { navController.navigate(Screen.GroupChatSettings(groupId)) },
                            )
                            Text(
                                text = groupName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { showRecordMenu = true }) { Text(if (previewMode) "地图" else "记录") }
                            DropdownMenu(expanded = showRecordMenu, onDismissRequest = { showRecordMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (previewMode) "返回聊天" else "对话地图") },
                                    enabled = nodes.isNotEmpty(),
                                    onClick = {
                                        showRecordMenu = false
                                        selectionState.cancel()
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        previewMode = !previewMode
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("导出记录") },
                                    enabled = nodes.isNotEmpty(),
                                    onClick = {
                                        showRecordMenu = false
                                        previewMode = false
                                        showAttachments = false
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        selectionState.start(nodes.map { it.id })
                                    },
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                showAttachments = false
                                showSummaryDialog = true
                            },
                            enabled = canSummarize,
                        ) {
                            Text(if (isSummarizing) "正在总结" else "总结")
                        }
                        IconButton(onClick = { navController.navigate(Screen.GroupChatSettings(groupId)) }) {
                            Icon(HugeIcons.Settings03, contentDescription = "群聊设置")
                        }
                        if (isGenerating || awaitingManualSpeaker) {
                            TextButton(onClick = vm::stop) { Text(stringResource(R.string.stop)) }
                        }
                    },
                    )
                    UsageBudgetBanner()
                }
            },
            bottomBar = {
                if (inputAssistant != null) {
                    ChatInput(
                        state = inputState,
                        loading = isGenerating || awaitingManualSpeaker,
                        settings = settings,
                        assistant = inputAssistant,
                        chatModel = chatModel,
                        hazeState = hazeState,
                        onUpdateChatModel = vm::updateChatModel,
                        onUpdateAssistant = vm::updateInputAssistant,
                        onMoreClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            showAttachments = true
                        },
                        onCancelClick = vm::stop,
                        onSendClick = { sendInput(answer = true) },
                        onLongSendClick = { sendInput(answer = false) },
                        onSendSticker = { url ->
                            when {
                                vm.isGenerating.value -> toaster.show("请先等待生成完成或停止生成", type = ToastType.Warning)
                                vm.members().isEmpty() -> toaster.show("请先在群聊设置中添加或启用成员", type = ToastType.Error)
                                chatModel == null -> toaster.show("请先选择模型", type = ToastType.Error)
                                else -> vm.sendSticker(url)
                            }
                        },
                        onManageStickers = { navController.navigate(Screen.SettingStickers) },
                    )
                } else {
                    TextButton(
                        onClick = { navController.navigate(Screen.GroupChatSettings(groupId)) },
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    ) {
                        Text("请先在群聊设置中添加或启用成员")
                    }
                }
            },
        ) { innerPadding ->
            if (settings.displaySetting.enableAutoScroll) {
                LaunchedEffect(listState, innerPadding, density) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visibleItems ->
                        val lastItem = visibleItems.lastOrNull() ?: return@collect
                        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
                        val inputTop = listState.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt()
                        if (!overviewVisible && !currentSelecting && currentExportConversation == null && !listState.isScrollInProgress && generatingState &&
                            lastItem.offset + lastItem.size <= inputTop - 8
                        ) {
                            listState.requestScrollToItem(lastContentIndex)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalSettings provides groupSettings) {
                ChatFontProvider(displaySetting = settings.displaySetting) {
                    Box(
                        modifier = Modifier.fillMaxSize().clipToBounds().previewSwipeGesture(
                            progress = previewProgress,
                            scope = scope,
                            layoutDirection = layoutDirection,
                            canEnterPreview = { currentNodes.isNotEmpty() && !currentSelecting && currentExportConversation == null },
                            isPreviewMode = { currentPreviewMode },
                            onPreviewModeChange = {
                                previewMode = it
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                        ),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val sign = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                    translationX = -previewProgress.value * size.width * sign
                                    alpha = 1f - previewProgress.value
                                }
                                .groupChatTopFadingEdge(innerPadding.calculateTopPadding() + 24.dp)
                                .hazeSource(hazeState),
                        state = listState,
                        contentPadding = PaddingValues(16.dp) + PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = 32.dp + innerPadding.calculateBottomPadding(),
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top,
                    ) {
                        itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                            val callRange = callRanges[index]
                            val callKey = callRange?.let { nodes[it.first].id }
                            val expanded = callKey != null && expandedCalls[callKey] == true
                            val needsApproval = callRange?.first in pendingCallRanges
                            val detailsVisible = callRange == null || expanded || selectionState.selecting || needsApproval
                            val headerVisible = callRange != null && index == callRange.first && !selectionState.selecting
                            Column(
                                modifier = if (detailsVisible || headerVisible) Modifier.padding(bottom = 12.dp) else Modifier,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                            if (headerVisible) {
                                VoiceCallGroupHeader(node.currentMessage, checkNotNull(callRange).count(), expanded || needsApproval) {
                                    expandedCalls[checkNotNull(callKey)] = !expanded
                                }
                            }
                            if (detailsVisible) {
                            val message = node.messages.getOrNull(node.selectIndex) ?: return@itemsIndexed
                            val speaker = if (message.role == MessageRole.ASSISTANT) {
                                speakerMap[message.id]?.let(assistantsById::get)
                            } else null
                            val isUser = message.role == MessageRole.USER
                            val showAvatar = !message.isContextCheckpoint &&
                                (isUser || message.role == MessageRole.ASSISTANT)
                            val senderName = if (isUser) {
                                settings.displaySetting.userNickname.ifBlank { "用户" }
                            } else {
                                speaker?.name?.takeIf { it.isNotBlank() } ?: "角色"
                            }
                            val senderAvatar = if (isUser) settings.displaySetting.userAvatar
                                else speaker?.avatar ?: Avatar.Dummy
                            ListSelectableItem(
                                key = node.id,
                                selectedKeys = selectionState.selectedItems,
                                enabled = selectionState.selecting,
                                onSelectChange = {
                                    if (node.id in selectionState.selectedItems) selectionState.selectedItems.remove(node.id)
                                    else selectionState.selectedItems.add(node.id)
                                },
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (showAvatar && !isUser) {
                                    GroupMessageAvatar(
                                        senderName,
                                        senderAvatar,
                                        activity = speaker?.id?.let { memberActivities[it]?.content ?: "暂无活动记录" },
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (showAvatar && !isUser) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = senderName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            speaker?.let {
                                                GroupActivityLabel(memberActivities[it.id]?.content)
                                            }
                                        }
                                    }
                                    ChatMessage(
                                        showMessageHeader = false,
                                        node = node,
                                        model = vm.modelOf(message),
                                        assistant = speaker?.copy(useAssistantAvatar = true),
                                        loading = isGenerating && !isSummarizing && !awaitingManualSpeaker && message.id == replyMessageId &&
                                            message.role == MessageRole.ASSISTANT,
                                        lastMessage = index == nodes.lastIndex,
                                        isFavorite = node.isFavorite,
                                        onFork = {},
                                        onRegenerate = { vm.regenerate(message) },
                                        onEdit = {},
                                        onShare = {
                                            previewMode = false
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            selectionState.start(nodes.take(index + 1).map { it.id })
                                        },
                                        onDelete = { vm.deleteMessage(message) },
                                        onUpdate = { vm.selectNode(it.id, it.selectIndex) },
                                        onToggleFavorite = null,
                                        onTranslate = null,
                                        showVoiceCallDetails = callRange != null,
                                    )
                                }
                                if (showAvatar && isUser) {
                                    GroupMessageAvatar(senderName, senderAvatar)
                                }
                            }
                            }
                            }
                            }
                        }
                        itemsIndexed(summaryStages, key = { _, stage -> "summary-${stage.id}" }) { _, stage ->
                            Text(
                                text = "已总结 · ${stage.sourceMessageIds.size}条消息 · ${stage.topics.size}个主题",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                            )
                        }
                        if (isSummarizing) {
                            item(key = "summarizing") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ContainedLoadingIndicator(modifier = Modifier.size(28.dp))
                                    Text(
                                        text = "正在总结，原始记录会保留…",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = vm::stop) { Text("取消") }
                                }
                            }
                        } else if (awaitingManualSpeaker) {
                            item(key = "awaitingManualSpeaker") {
                                Text(
                                    text = "等待选择发言成员…",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (isGenerating) {
                            item(key = "typing") {
                                val name = settings.assistants
                                    .firstOrNull { it.id == currentSpeakerId }
                                    ?.name
                                    ?.ifBlank { null }
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ContainedLoadingIndicator(modifier = Modifier.size(28.dp))
                                    Text(
                                        text = if (name != null) "$name 正在输入…" else "正在输入…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item(key = "scrollBottom") {
                            Spacer(Modifier.fillMaxWidth().height(5.dp))
                        }
                    }
                    if (previewProgress.value > 0f) {
                        conversation?.let { record ->
                            Box(Modifier.fillMaxSize().graphicsLayer {
                                val sign = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                translationX = (1f - previewProgress.value) * size.width * sign
                                alpha = 0.4f + previewProgress.value * 0.6f
                            }) {
                                ChatListPreview(
                                    innerPadding = innerPadding,
                                    conversation = record,
                                    chatListState = listState,
                                    listState = previewListState,
                                    settings = settings,
                                    hazeState = hazeState,
                                    groupMode = true,
                                    onJumpToMessage = { index ->
                                        previewMode = false
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        if (index in currentNodes.indices) {
                                            val target = currentNodes.voiceCallRanges()[index]?.first ?: index
                                            listState.requestScrollToItem(target)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    ChatSelectionToolbar(
                        state = selectionState,
                        allIds = nodes.map { it.id },
                        onConfirm = {
                            conversation?.let { record ->
                                val selected = record.messageNodes.filter { it.id in selectionState.selectedItems }
                                if (selected.isNotEmpty()) {
                                    exportConversation = record
                                    exportMessages = selected.map { it.currentMessage }
                                    exportNames = record.chatRecordSenderNames(settings, groupMode = true)
                                    exportTitle = groupName
                                    selectionState.selecting = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(innerPadding).offset(y = (-48).dp),
                    )
                    conversationLoadError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                    ErrorCardsDisplay(
                        errors = errors,
                        onDismissError = vm::dismissError,
                        onClearAllErrors = vm::clearAllErrors,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        }
        // 保持结果接收器在群页面中注册，面板状态重建后仍可接收系统保存结果。
        conversation?.let { current ->
            ChatExportSheet(
                visible = exportConversation != null,
                onDismissRequest = {
                    exportConversation = null
                    exportMessages = emptyList()
                    exportNames = emptyMap()
                    selectionState.cancel()
                },
                conversation = exportConversation ?: current,
                selectedMessages = exportMessages,
                senderNames = exportNames,
                title = exportTitle,
            )
        }
        if (awaitingManualSpeaker) {
            ModalBottomSheet(
                onDismissRequest = { vm.chooseManualSpeaker(null) },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("选择接话成员", style = MaterialTheme.typography.titleLarge)
                    Text("选中的成员回复一次；跳过或关闭则按名单轮流。")
                    vm.members().forEach { member ->
                        TextButton(
                            onClick = { vm.chooseManualSpeaker(member.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            GroupMessageAvatar(
                                name = member.name,
                                avatar = member.avatar,
                                activity = memberActivities[member.id]?.content ?: "暂无活动记录",
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(member.name.ifBlank { "未命名角色" })
                                GroupActivityLabel(memberActivities[member.id]?.content)
                            }
                        }
                    }
                    TextButton(
                        onClick = { vm.chooseManualSpeaker(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("跳过，按名单轮流") }
                    TextButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) {
                        Text("停止本次接话")
                    }
                }
            }
        }
        if (showSummaryDialog) {
            GroupSummaryDialog(
                defaultKeepRecentMessages = summaryKeepRecentMessages,
                canSummarize = canSummarize,
                onDismiss = { showSummaryDialog = false },
                onSummarize = { additionalPrompt, keepRecentMessages ->
                    if (vm.summarize(additionalPrompt, keepRecentMessages)) {
                        showSummaryDialog = false
                    }
                },
            )
        }
        if (showAttachments && !awaitingManualSpeaker && inputAssistant != null) {
            GroupChatAttachmentSheet(
                actions = attachmentPickerActions,
                allowAudioVideo = chatModel?.findProvider(settings.providers) is ProviderSetting.Google,
                onDismiss = { showAttachments = false },
            )
        }
    }
}

@Composable
private fun GroupSummaryDialog(
    defaultKeepRecentMessages: Int,
    canSummarize: Boolean,
    onDismiss: () -> Unit,
    onSummarize: (String, Int) -> Unit,
) {
    var additionalPrompt by rememberSaveable { mutableStateOf("") }
    var keepRecentMessages by rememberSaveable { mutableStateOf(defaultKeepRecentMessages) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("总结当前群聊") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "按主题生成阶段总结，仅供当前会话后续接话参考，不会删除原始聊天记录。总结会调用模型并产生相应费用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CardGroup {
                    formItem(headlineContent = { Text("附加主题重点（可选）") }) {
                        OutlinedTextField(
                            value = additionalPrompt,
                            onValueChange = { additionalPrompt = it },
                            placeholder = { Text("例如：关系变化、未完成的约定") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 5,
                            enabled = canSummarize,
                        )
                    }
                    formItem(
                        headlineContent = { Text("保留最近 $keepRecentMessages 条消息") },
                        supportingContent = { Text("这些消息不纳入本次总结；0 表示不预留最近消息。") },
                    ) {
                        Slider(
                            value = keepRecentMessages.toFloat(),
                            onValueChange = { keepRecentMessages = it.roundToInt() },
                            valueRange = 0f..50f,
                            steps = 49,
                            enabled = canSummarize,
                        )
                    }
                }
                if (!canSummarize) {
                    Text(
                        "当前无法总结，请等待正在进行的任务结束，并确认会话中已有消息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSummarize(additionalPrompt.trim(), keepRecentMessages) },
                enabled = canSummarize,
            ) { Text("开始总结") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun GroupSummaryStageCard(stage: GroupSummaryStage, stageNumber: Int, valid: Boolean, archived: Boolean) {
    var expanded by rememberSaveable(stage.id.toString()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CardGroup {
                item(
                    onClick = { expanded = !expanded },
                    headlineContent = {
                        Text("阶段 $stageNumber", style = MaterialTheme.typography.titleSmallEmphasized)
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("覆盖 ${stage.sourceMessageIds.size} 条消息 · ${stage.topics.size} 个主题")
                            Text(
                                text = if (valid) "有效" else if (archived) "历史版本，仅供查阅" else "因历史变化已失效",
                                color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    trailingContent = { Text(if (expanded) "收起" else "展开") },
                )
            }
            if (expanded) {
                Text(
                    text = if (valid) "仅为当前会话资料，原始记录仍保留。"
                        else "此阶段不再用于后续接话，仅保留供查看；原始记录仍保留。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stage.topics.forEachIndexed { index, topic ->
                    var topicExpanded by rememberSaveable(stage.id.toString(), index) { mutableStateOf(false) }
                    CardGroup {
                        item(
                            onClick = { topicExpanded = !topicExpanded },
                            headlineContent = { Text(topic.title) },
                            trailingContent = { Text(if (topicExpanded) "收起" else "展开") },
                        )
                        if (topicExpanded) {
                            formItem {
                                SelectionContainer {
                                    Text(topic.content, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 群聊专用头像；角标只表示活动记录，不表示在线或离线。 */
@Composable
internal fun GroupMessageAvatar(name: String, avatar: Avatar, activity: String? = null) {
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(modifier = Modifier.size(36.dp)) {
        if (avatar == Avatar.Dummy) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = name.trim().take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }
        } else {
            UIAvatar(name = name, value = avatar, modifier = Modifier.fillMaxSize(), shape = shape)
        }
        if (activity != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                    .semantics { contentDescription = "角色活动：$activity" },
                shape = shape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("记", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 两处共用最新普通活动文案；点击可查看被省略的全文。 */
@Composable
internal fun GroupActivityLabel(content: String?) {
    val text = content?.takeIf { it.isNotBlank() } ?: "暂无活动记录"
    var showDetail by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDetail = true },
        modifier = Modifier.widthIn(max = 200.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "活动 · $text",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("当前活动记录") },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatAttachmentSheet(
    actions: ChatAttachmentPickerActions,
    allowAudioVideo: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            val options = buildList {
                add(R.string.take_picture to actions.onTakePicture)
                add(R.string.photo to actions.onPickImage)
                add(R.string.sketch to actions.onSketch)
                if (allowAudioVideo) {
                    add(R.string.video to actions.onPickVideo)
                    add(R.string.audio to actions.onPickAudio)
                }
                add(R.string.upload_file to actions.onPickFile)
            }
            options.forEach { (label, action) ->
                TextButton(
                    onClick = {
                        onDismiss()
                        action()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

// 与单聊相同的顶部渐隐，仅作用于消息，不遮盖背景。
private fun Modifier.groupChatTopFadingEdge(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val fadeHeight = height.toPx()
        val brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.6f to Color.Black.copy(alpha = 0.15f),
            1f to Color.Black,
            startY = 0f,
            endY = fadeHeight,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush, size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
        }
    }
