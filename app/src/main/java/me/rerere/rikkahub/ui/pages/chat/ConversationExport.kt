package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.ChatMessageServerToolStep
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.message.tools.memoryToolTitle
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.components.LocalExportContext
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.rememberToasterState
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.Uuid

private enum class ChatExportFormat(val extension: String, val mimeType: String) {
    Markdown("md", "text/markdown"),
    Image("png", "image/png"),
}

private enum class ChatExportAction { Save, Share }

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>,
    // 群聊调用者传入「当前版本消息 ID → 发言人名称」，不能传模型 ID。
    senderNames: Map<Uuid, String> = emptyMap(),
    title: String? = null,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val currentConversationId by rememberUpdatedState(conversation.id.toString())
    val dismiss by rememberUpdatedState(onDismissRequest)
    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    var format by rememberSaveable { mutableStateOf(ChatExportFormat.Markdown) }
    var exporting by remember { mutableStateOf<ChatExportAction?>(null) }
    // 只保存已经生成好的文件，不在 picker 返回后重新读取会话或选中项。
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = exporting != null || pendingSavePath != null
    val currentBusy by rememberUpdatedState(busy)

    fun reportFailure(error: Exception) {
        if (error is CancellationException) throw error
        error.printStackTrace()
        toaster.show("导出失败：${error.message ?: error.javaClass.simpleName}", type = ToastType.Error)
    }

    fun onSaveDocumentResult(uri: Uri?) {
        val source = pendingSavePath?.let(::File) ?: return
        val requestConversationId = pendingConversationId
        pendingSavePath = null
        pendingConversationId = null
        // SAF 的取消属于正常结果，保留面板和选择，不弹错误或成功提示。
        if (uri == null) {
            source.delete()
            return
        }
        exporting = ChatExportAction.Save
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法打开所选文件进行写入")
                    output.use { out ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                toaster.show("已保存导出文件", type = ToastType.Success)
                if (requestConversationId == currentConversationId) dismiss()
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                source.delete()
                exporting = null
            }
        }
    }

    val saveMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ChatExportFormat.Markdown.mimeType)
    ) { onSaveDocumentResult(it) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ChatExportFormat.Image.mimeType)
    ) { onSaveDocumentResult(it) }

    fun export(action: ChatExportAction) {
        if (exporting != null || pendingSavePath != null) return
        val requestConversationId = conversation.id.toString()
        val requestFormat = format
        // 每个选中原始消息都经过统一的聊天记录脱敏，不合成 config 中的群总结。
        val request = try {
            ChatExportSnapshot(
                title = title ?: conversation.title,
                messages = snapshotExportMessages(
                    selectedMessages.map { it.forChatRecord() },
                    exportOptions.includeReasoning,
                ),
                senderNames = senderNames.toMap(),
                options = exportOptions,
                exportedAt = LocalDateTime.now().toLocalString(),
            )
        } catch (error: Exception) {
            reportFailure(error)
            return
        }
        val requestSettings = settings
        val requestDensity = Density(density.density, density.fontScale)
        exporting = action
        scope.launch {
            var generatedFile: File? = null
            var handedOff = false
            try {
                require(request.messages.isNotEmpty()) { "没有选中可导出的消息" }
                val file = when (requestFormat) {
                    ChatExportFormat.Markdown -> writeMarkdownExport(context, request)
                    ChatExportFormat.Image -> writeImageExport(context, requestDensity, request, requestSettings)
                }
                generatedFile = file
                currentCoroutineContext().ensureActive()
                when (action) {
                    ChatExportAction.Save -> {
                        pendingSavePath = file.absolutePath
                        pendingConversationId = requestConversationId
                        try {
                            when (requestFormat) {
                                ChatExportFormat.Markdown -> saveMarkdownLauncher.launch(file.name)
                                ChatExportFormat.Image -> saveImageLauncher.launch(file.name)
                            }
                            handedOff = true
                        } catch (error: Exception) {
                            pendingSavePath = null
                            pendingConversationId = null
                            throw error
                        }
                    }
                    ChatExportAction.Share -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        shareFile(context, uri, requestFormat.mimeType)
                        handedOff = true // 接收方异步读取，不能在这里删除分享文件。
                        // 系统分享面板已打开，不声称对方已接收或保存。
                        if (requestConversationId == currentConversationId) dismiss()
                    }
                }
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                if (!handedOff) generatedFile?.delete()
                exporting = null
            }
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) dismiss() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                confirmValueChange = { it != SheetValue.Hidden || !currentBusy },
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_export_format),
                    style = MaterialTheme.typography.titleLarge,
                )
                ConnectedChoiceButtons(
                    options = ChatExportFormat.entries,
                    selected = format,
                    onSelect = { if (!busy) format = it },
                ) { option, selected ->
                    ConnectedChoiceLabel(
                        text = if (option == ChatExportFormat.Markdown) "Markdown" else "PNG 长图",
                        selected = selected,
                        icon = if (option == ChatExportFormat.Markdown) HugeIcons.File02 else HugeIcons.Image02,
                    )
                }
                Text(
                    text = if (format == ChatExportFormat.Markdown) {
                        "导出选中消息的当前版本。图片尽量内嵌，文档、音频和视频仅保留原链接，不是完整附件备份。"
                    } else {
                        "导出选中消息的长图，最高 10000dp、最多 2400 万像素；超限请减少消息或使用 Markdown。附件不作为完整文件备份。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardGroup {
                    switchItem(
                        checked = exportOptions.includeReasoning,
                        enabled = !busy,
                        onCheckedChange = { exportOptions = exportOptions.copy(includeReasoning = it) },
                    ) { Text(stringResource(R.string.chat_page_export_include_reasoning)) }
                    if (format == ChatExportFormat.Image) {
                        switchItem(
                            checked = exportOptions.expandReasoning,
                            enabled = !busy && exportOptions.includeReasoning,
                            onCheckedChange = { exportOptions = exportOptions.copy(expandReasoning = it) },
                        ) { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatExportButton(
                        text = "另存为文件", icon = HugeIcons.Download04,
                        loading = exporting == ChatExportAction.Save || pendingSavePath != null,
                        enabled = !busy, tonal = true,
                        onClick = { export(ChatExportAction.Save) }, modifier = Modifier.weight(1f),
                    )
                    ChatExportButton(
                        text = "分享", icon = HugeIcons.Share04,
                        loading = exporting == ChatExportAction.Share,
                        enabled = !busy, tonal = false,
                        onClick = { export(ChatExportAction.Share) }, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatExportButton(
    text: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    tonal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val content: @Composable RowScope.() -> Unit = {
        if (loading) LoadingIndicator(modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        else Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    if (tonal) {
        FilledTonalButton(
            onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = modifier.heightIn(min = height), content = content,
        )
    } else {
        Button(
            onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = modifier.heightIn(min = height), content = content,
        )
    }
}

private suspend fun writeMarkdownExport(context: Context, request: ChatExportSnapshot): File =
    withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val markdown = buildMarkdownExport(request) { image ->
            coroutineContext.ensureActive()
            // 不允许编码失败变成 Markdown 中的 null 图片链接。
            image.encodeBase64().getOrThrow().base64
        }
        currentCoroutineContext().ensureActive()
        File.createTempFile("chat-export-", ".md", context.appTempFolder).apply {
            writeText(markdown, Charsets.UTF_8)
        }
    }

private suspend fun writeImageExport(
    context: Context,
    density: Density,
    request: ChatExportSnapshot,
    settings: Settings,
): File {
    val bitmap = try {
        renderExportBitmap(context, density) {
            CompositionLocalProvider(LocalSettings provides settings) {
                ExportedChatImage(request)
            }
        }
    } catch (error: OutOfMemoryError) {
        throw IllegalStateException("聊天长图内存不足，请减少选中消息或使用 Markdown 导出", error)
    }
    try {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val file = File.createTempFile("chat-export-", ".png", context.appTempFolder)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 图片编码失败" }
            }
            file
        }
    } finally {
        bitmap.recycle()
    }
}

// 不使用公共 BitmapComposer：其异步 Handler 不传播异常或取消，且会在高度上限处截断。
private suspend fun renderExportBitmap(
    context: Context,
    density: Density,
    content: @Composable () -> Unit,
): Bitmap = withContext(Dispatchers.Main.immediate) {
    val activity = context.getActivity() ?: error("无法获取导出所需的活动页面")
    check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭，无法导出图片" }
    val decor = activity.window.decorView as ViewGroup
    val width = (540 * density.density).roundToInt()
    val maxHeight = (EXPORT_MAX_IMAGE_HEIGHT_DP * density.density).roundToInt()
    // INVISIBLE 会让 Compose 的窗口可见性变为 false，暂停异步重组。
    // 仅让父容器透明；直接绘制子 ComposeView 时不会把父 alpha 带进位图。
    val container = FrameLayout(activity).apply {
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            CompositionLocalProvider(LocalExportContext provides true, LocalDensity provides density) {
                content()
            }
        }
    }
    try {
        container.addView(view)
        decor.addView(container)
        fun measure() {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
            )
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
        }
        measure()
        // 与原有截图一样只等待一次异步布局，不保证远程附件在截图前下载完成。
        delay(100)
        currentCoroutineContext().ensureActive()
        check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭，无法导出图片" }
        measure()
        checkExportImageSize(view.measuredWidth, view.measuredHeight, maxHeight)
        view.drawToBitmap()
    } finally {
        view.disposeComposition()
        decor.removeView(container)
    }
}

@Composable
private fun ExportedChatImage(request: ChatExportSnapshot) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(LocalNavController provides navigator, LocalToaster provides toasterState) {
            Surface(modifier = Modifier.width(540.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = request.title, style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                            Text(
                                text = "${request.exportedAt}  rikka-ai.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null, modifier = Modifier.size(60.dp),
                        )
                    }
                    request.messages.forEach { message ->
                        ExportedChatMessage(message, exportSenderName(message, request.senderNames), request.options)
                    }
                    Text(
                        text = stringResource(R.string.export_image_warning), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportedChatMessage(message: UIMessage, senderName: String, options: ExportOptions) {
    val parts = message.parts
    if (parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) } ?: message.snapshotModel()
    // 每条消息都显示发言人，图标仅作模型装饰，不能用模型 ID 代替群成员身份。
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (message.role == MessageRole.ASSISTANT) {
            AutoAIIcon(name = model?.modelId ?: "AI", modifier = Modifier.size(28.dp))
        }
        Text(text = senderName, style = MaterialTheme.typography.titleMedium)
    }
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    Column(
        modifier = Modifier.widthIn(max = (540 * 0.9).dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
    ) {
        groupedParts.forEach { block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> if (block.steps.isNotEmpty()) {
                    ChainOfThought(steps = block.steps, collapsedVisibleCount = block.steps.size) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> ExportedReasoningStep(step.reasoning, options.expandReasoning)
                            is ThinkingStep.ToolStep -> ExportedToolStep(step.tool)
                            is ThinkingStep.ServerToolStep -> ChatMessageServerToolStep(tool = step.tool)
                        }
                    }
                }
                is MessagePartBlock.ChartBlock -> {
                    val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                    spec?.let { ChartCard(spec = it) }
                }
                is MessagePartBlock.ContentBlock -> when (val part = block.part) {
                    is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            if (message.role == MessageRole.USER || settings.displaySetting.showAssistantBubble) {
                                Card(
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (message.role == MessageRole.USER) {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        } else MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                ) { MarkdownBlock(content = part.text, modifier = Modifier.padding(8.dp)) }
                            } else MarkdownBlock(content = part.text)
                        }
                    }
                    is UIMessagePart.Image -> AsyncImage(
                        model = ImageRequest.Builder(context).data(part.url).allowHardware(false).crossfade(false).build(),
                        contentDescription = "图片",
                        modifier = Modifier.sizeIn(maxHeight = 300.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    is UIMessagePart.Document -> Text("附件：${part.fileName}", style = MaterialTheme.typography.bodySmall)
                    is UIMessagePart.Audio -> Text("音频：${part.url}", style = MaterialTheme.typography.bodySmall)
                    is UIMessagePart.Video -> Text("视频：${part.url}", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(reasoning: UIMessagePart.Reasoning, expanded: Boolean) {
    val duration = reasoning.finishedAt?.let { it - reasoning.createdAt }
        ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)
    ControlledChainOfThoughtStep(
        expanded = expanded, onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink), contentDescription = null,
                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else null,
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(tool: UIMessagePart.Tool) {
    val title = when (tool.toolName) {
        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }
        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> memoryToolTitle(tool.toolName, runCatching { tool.inputAsJson() }.getOrNull())
            ?: stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true, onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    MemoryToolNames.DELETE -> HugeIcons.Book02
                    in MemoryToolNames.WRITES -> HugeIcons.Book04
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            Text(
                text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false, content = null,
    )
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("聊天导出", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_page_export_share_via)))
}
