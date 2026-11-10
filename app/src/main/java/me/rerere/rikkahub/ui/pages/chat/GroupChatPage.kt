package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.UsageBudgetBanner
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.CardGroup
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
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val awaitingManualSpeaker by vm.awaitingManualSpeaker.collectAsStateWithLifecycle()
    val currentSpeakerId by vm.currentSpeakerId.collectAsStateWithLifecycle()
    val replyMessageId by vm.replyMessageId.collectAsStateWithLifecycle()
    val generationError by vm.error.collectAsStateWithLifecycle()

    val inputState = vm.inputState
    val inputAssistant = vm.members().firstOrNull()
    val chatModel = settings.findModelById(groupChat?.chatModelId)
        ?: settings.findModelById(inputAssistant?.chatModelId, settings.chatModelId)
    val groupName = groupChat?.name?.ifBlank { "未命名群聊" } ?: "群聊"
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val nodes = conversation?.messageNodes.orEmpty()
    // 消息行显式依赖页面订阅的角色卡和发言映射，头像修改后无需重新进入群聊。
    val assistantsById = remember(settings.assistants) { settings.assistants.associateBy { it.id } }
    val speakerMap = conversation?.config?.speakerMap.orEmpty()
    val generatingState by rememberUpdatedState(isGenerating)
    val lastNodeIndex by rememberUpdatedState(nodes.lastIndex)
    var showAttachments by remember(groupId) { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = settings,
        onAttachmentAdded = { showAttachments = false },
    )

    LaunchedEffect(groupId, nodes.size) {
        if (nodes.isNotEmpty()) {
            listState.requestScrollToItem(nodes.lastIndex + 10)
        }
    }
    ImeLazyListAutoScroller(lazyListState = listState)

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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                        if (!listState.isScrollInProgress && generatingState &&
                            lastItem.offset + lastItem.size <= inputTop - 8
                        ) {
                            listState.requestScrollToItem(lastNodeIndex + 10)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalSettings provides groupSettings) {
                ChatFontProvider(displaySetting = settings.displaySetting) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .groupChatTopFadingEdge(innerPadding.calculateTopPadding() + 24.dp)
                            .hazeSource(hazeState),
                        state = listState,
                        contentPadding = PaddingValues(16.dp) + PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = 32.dp + innerPadding.calculateBottomPadding(),
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (showAvatar && !isUser) {
                                    GroupMessageAvatar(senderName, senderAvatar)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (showAvatar && !isUser) {
                                        Text(
                                            text = senderName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(bottom = 4.dp),
                                        )
                                    }
                                    ChatMessage(
                                        showMessageHeader = false,
                                        node = node,
                                        model = vm.modelOf(message),
                                        assistant = speaker?.copy(useAssistantAvatar = true),
                                        loading = isGenerating && !awaitingManualSpeaker && message.id == replyMessageId &&
                                            message.role == MessageRole.ASSISTANT,
                                        lastMessage = index == nodes.lastIndex,
                                        isFavorite = node.isFavorite,
                                        onFork = {},
                                        onRegenerate = { vm.regenerate(message) },
                                        onEdit = {},
                                        onShare = {},
                                        onDelete = { vm.deleteMessage(message) },
                                        onUpdate = { vm.selectNode(it.id, it.selectIndex) },
                                        onToggleFavorite = null,
                                        onTranslate = null,
                                    )
                                }
                                if (showAvatar && isUser) {
                                    GroupMessageAvatar(senderName, senderAvatar)
                                }
                            }
                        }
                        if (awaitingManualSpeaker) {
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
                        generationError?.let { error ->
                            item(key = "generationError") {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                )
                            }
                        }
                        item(key = "scrollBottom") {
                            Spacer(Modifier.fillMaxWidth().height(5.dp))
                        }
                    }
                }
            }
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
                            UIAvatar(
                                name = member.name,
                                value = member.avatar,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(member.name.ifBlank { "未命名角色" }, modifier = Modifier.padding(start = 8.dp))
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
        if (showAttachments && !awaitingManualSpeaker && inputAssistant != null) {
            GroupChatAttachmentSheet(
                actions = attachmentPickerActions,
                allowAudioVideo = chatModel?.findProvider(settings.providers) is ProviderSetting.Google,
                onDismiss = { showAttachments = false },
            )
        }
    }
}

/** 群聊专用头像，不受单聊头像开关和动态形状设置影响。 */
@Composable
private fun GroupMessageAvatar(name: String, avatar: Avatar) {
    val shape = androidx.compose.foundation.shape.CircleShape
    if (avatar == Avatar.Dummy) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = shape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.trim().take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    } else {
        UIAvatar(
            name = name,
            value = avatar,
            modifier = Modifier.size(36.dp),
            shape = shape,
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
