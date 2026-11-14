package me.rerere.rikkahub.ui.pages.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.ui.context.LocalNavController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BasicAlertDialog
import me.rerere.rikkahub.data.model.validGroupSummaryStages
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.data.model.DEFAULT_GROUP_CHAT_SYSTEM_PROMPT
import me.rerere.rikkahub.data.model.GroupActivationStrategy
import me.rerere.rikkahub.data.model.normalizeChatContextCount
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// TODO(i18n): 文案先写中文，后续统一提到 strings.xml
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatSettingsPage(
    groupId: String? = null,
    vm: GroupChatSettingsVM = koinViewModel(
        parameters = { parametersOf(groupId ?: GroupChatSettingsVM.GLOBAL_SETTINGS_ID) },
    ),
) {
    val settings by vm.settings.collectAsState()
    val memberActivities by vm.memberActivities.collectAsStateWithLifecycle()
    val summaryConversation by vm.summaryConversation.collectAsStateWithLifecycle()
    val historyStages = remember(summaryConversation) {
        (summaryConversation?.config?.groupSummaryArchive.orEmpty() +
            summaryConversation?.config?.groupSummaryStages.orEmpty()).distinctBy { it.id }
    }
    val validHistoryIds = remember(summaryConversation) {
        summaryConversation?.validGroupSummaryStages().orEmpty().map { it.id }.toSet()
    }
    val archivedHistoryIds = summaryConversation?.config?.groupSummaryArchive.orEmpty().map { it.id }.toSet()
    var showSummaryHistory by rememberSaveable(groupId) { mutableStateOf(false) }
    val current = vm.draftGroup
    val isGlobal = vm.isGlobal
    val config = if (isGlobal) vm.draftDefaults else current?.resolved(settings.groupChatDefaults) ?: settings.groupChatDefaults
    val chatContextCount = normalizeChatContextCount(config.singleChatContextCount)
    val summaryThreshold = config.autoSummaryMessageThreshold.coerceIn(40, 300)
    val summaryKeepMaximum = minOf(50, summaryThreshold - 1)
    val summaryKeepRecent = config.summaryKeepRecentMessages.coerceIn(0, summaryKeepMaximum)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    val nav = LocalNavController.current
    val context = LocalContext.current
    val requestExit = {
        if (!vm.saving) {
            if (vm.dirty) showUnsavedDialog = true else nav.popBackStack()
        }
        Unit
    }
    val save: (Boolean) -> Unit = { exit ->
        vm.save {
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            showUnsavedDialog = false
            if (exit) nav.popBackStack()
        }
    }
    BackHandler(enabled = vm.dirty || vm.saving) { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = requestExit, enabled = !vm.saving) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                title = { Text(if (isGlobal) "群聊全局默认设置" else "群聊设置") },
                actions = {
                    if (!isGlobal && current != null) {
                        IconButton(onClick = { showDeleteDialog = true }, enabled = !vm.saving) {
                            Icon(HugeIcons.Delete01, contentDescription = "删除群聊")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(if (vm.dirty) "有未保存的修改" else "设置未修改", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { save(false) }, enabled = vm.ready && !vm.saving && (isGlobal || current != null),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.saving) "保存中…" else "保存")
                    }
                }
            }
        },
    ) { innerPadding ->
        if (!vm.ready || settings.init || (!isGlobal && current == null)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (settings.init) "正在加载设置" else "群聊不存在")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current != null) {
                item("avatar") {
                    CardGroup {
                        item(
                            headlineContent = { Text("群头像") },
                            supportingContent = { Text("点击头像更换，大图会自动缩小压缩；在群聊列表和聊天顶部显示，修改后点击保存生效。") },
                            leadingContent = {
                                UIAvatar(
                                    name = current.name.ifBlank { "未命名群聊" },
                                    value = current.avatar,
                                    modifier = Modifier.size(56.dp),
                                    onUpdate = if (vm.saving) null else vm::setAvatar,
                                    imageMaxSize = 1024,
                                )
                            },
                        )
                    }
                }
                item("name") {
                    OutlinedTextField(
                        value = current.name,
                        onValueChange = vm::setName,
                        label = { Text("群名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
            item("inheritance") {
                CardGroup {
                    item(
                        headlineContent = { Text(if (isGlobal) "所有群聊的默认配置" else "群聊配置继承") },
                        supportingContent = {
                            Text(
                                if (isGlobal) "新群自动跟随这里的设置；已有群仅跟随标为继承的字段。"
                                else "继承字段随全局默认值实时变化；自定义字段仅对本群生效。",
                            )
                        },
                    )
                    if (!isGlobal) {
                        item(
                            onClick = if (vm.saving) null else vm::resetAllToDefaults,
                            headlineContent = { Text("全部恢复全局默认") },
                            supportingContent = { Text("仅将发言策略、自动接话轮数、间隔、上下文互通、自动总结及群聊提示词恢复继承，不修改群名、头像、成员或禁言设置；保存后生效。") },
                        )
                    }
                }
            }
            item("configuration") {
                CardGroup {
                    formItem(headlineContent = { Text("发言策略") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.activationStrategyOverride == null,
                                onReset = vm::resetStrategy,
                                onCustomize = { vm.setStrategy(config.activationStrategy) },
                            )
                        }
                        StrategyPicker(current = config.activationStrategy, onSelect = vm::setStrategy)
                    }
                    formItem(headlineContent = { Text("自动接话轮数") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.autoChatRoundsOverride == null,
                                onReset = vm::resetAutoRounds,
                                onCustomize = { vm.setAutoRounds(config.autoChatRounds) },
                            )
                        }
                        Text(
                            text = "${config.autoChatRounds} 轮（每位参与成员各回复 ${config.autoChatRounds} 条；手动点名仅所选成员参与）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = config.autoChatRounds.coerceIn(1, 10).toFloat(),
                            onValueChange = { vm.setAutoRounds(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                    }
                    formItem(headlineContent = { Text("自动接话间隔") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.autoModeDelayOverride == null,
                                onReset = vm::resetAutoDelay,
                                onCustomize = { vm.setAutoDelaySeconds(config.autoModeDelay) },
                            )
                        }
                        Text(
                            text = if (config.autoModeDelay <= 0) "立即接话" else "${config.autoModeDelay} 秒",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = config.autoModeDelay.coerceIn(0, 60).toFloat(),
                            onValueChange = { vm.setAutoDelaySeconds(it.toInt()) },
                            valueRange = 0f..60f,
                            steps = 59,
                        )
                        Text("仅在轮数大于 1 时生效", style = MaterialTheme.typography.bodySmall)
                    }
                    formItem(headlineContent = { Text("单聊/群聊上下文互通") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.singleChatContextCountOverride == null,
                                onReset = vm::resetSingleChatContextCount,
                                onCustomize = { vm.setSingleChatContextCount(chatContextCount) },
                            )
                        }
                        Text(
                            text = "每处读取最近 $chatContextCount 条消息",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = chatContextCount.toFloat(),
                            onValueChange = { vm.setSingleChatContextCount(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                        Text(
                            text = "群聊读取该角色与你的私聊最近 $chatContextCount 条消息；单聊从该角色参与的每个群读取最近 $chatContextCount 条消息。修改后需点击保存生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!isGlobal && current != null) {
                item("summary_history") {
                    CardGroup {
                        item(
                            onClick = { showSummaryHistory = true },
                            headlineContent = { Text("历史总结") },
                            supportingContent = {
                                Text(when {
                                    vm.summaryHistoryLoading -> "正在加载…"
                                    vm.summaryHistoryError != null -> "加载失败，点击查看"
                                    historyStages.isEmpty() -> "暂无历史总结"
                                    else -> "${historyStages.size} 份记录 · 可展开查看主题全文与历史版本"
                                })
                            },
                            trailingContent = { Text("查看") },
                        )
                    }
                }
            }
            item("auto_summary") {
                CardGroup(title = { Text("自动总结") }) {
                    formItem(
                        headlineContent = { Text("启用自动总结") },
                        trailingContent = {
                            Switch(
                                checked = config.autoSummaryEnabled,
                                onCheckedChange = vm::setAutoSummaryEnabled,
                                enabled = !vm.saving,
                            )
                        },
                    ) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.autoSummaryEnabledOverride == null,
                                onReset = vm::resetAutoSummaryEnabled,
                                onCustomize = { vm.setAutoSummaryEnabled(config.autoSummaryEnabled) },
                                enabled = !vm.saving,
                            )
                        }
                        Text(
                            "达到阈值后自动总结较早的群聊消息，会产生额外模型调用费用。仅压缩发送给模型的请求，不删除聊天历史；自动总结失败不会阻断聊天。修改后点击保存生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    formItem(headlineContent = { Text("自动总结消息阈值") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.autoSummaryMessageThresholdOverride == null,
                                onReset = vm::resetAutoSummaryMessageThreshold,
                                onCustomize = { vm.setAutoSummaryMessageThreshold(summaryThreshold) },
                                enabled = !vm.saving,
                            )
                        }
                        Text("达到 $summaryThreshold 条消息时触发", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = summaryThreshold.toFloat(),
                            onValueChange = { vm.setAutoSummaryMessageThreshold(it.toInt()) },
                            valueRange = 40f..300f,
                            steps = 259,
                            enabled = !vm.saving,
                        )
                    }
                    formItem(headlineContent = { Text("保留最近消息条数") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.summaryKeepRecentMessagesOverride == null,
                                onReset = vm::resetSummaryKeepRecentMessages,
                                onCustomize = { vm.setSummaryKeepRecentMessages(summaryKeepRecent) },
                                enabled = !vm.saving,
                            )
                        }
                        Text("保留最近 $summaryKeepRecent 条原始消息", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = summaryKeepRecent.toFloat(),
                            onValueChange = { vm.setSummaryKeepRecentMessages(it.toInt()) },
                            valueRange = 0f..summaryKeepMaximum.toFloat(),
                            steps = summaryKeepMaximum - 1,
                            enabled = !vm.saving,
                        )
                        Text(
                            "保留数量须小于总结阈值，最多 50 条；设为 0 表示不额外保留最近的原始消息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item("system_prompt") {
                CardGroup {
                    formItem(headlineContent = { Text("群聊提示词") }) {
                        if (!isGlobal) {
                            OverrideStatus(
                                inherited = current?.systemPromptOverride == null,
                                onReset = vm::resetSystemPrompt,
                                onCustomize = { vm.setSystemPrompt(config.systemPrompt) },
                                enabled = !vm.saving,
                            )
                        }
                        OutlinedTextField(
                            value = config.systemPrompt,
                            onValueChange = vm::setSystemPrompt,
                            label = { Text("群聊提示词") },
                            enabled = !vm.saving,
                            singleLine = false,
                            minLines = 5,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (isGlobal) "作为继承此项的群聊的附加提示词；留空表示不附加。修改后点击保存生效。"
                            else "在角色原有人设基础上附加；留空表示本群不附加群聊提示词，不等于继承。修改后点击保存生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { vm.setSystemPrompt(DEFAULT_GROUP_CHAT_SYSTEM_PROMPT) },
                            enabled = !vm.saving,
                        ) {
                            Text("恢复内置模板")
                        }
                    }
                }
            }
            if (current != null) {
                item("members_title") {
                    Text(
                        text = "成员（${current.memberIds.size}）",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                items(settings.assistants, key = { "member-${it.id}" }) { assistant ->
                    val joined = assistant.id in current.memberIds
                    val muted = assistant.id in current.disabledMemberIds
                    ListItem(
                        modifier = Modifier.toggleable(
                            value = joined,
                            onValueChange = { vm.setMemberJoined(assistant.id, it) },
                        ),
                        headlineContent = { Text(assistant.name.ifBlank { "未命名助手" }) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (muted) "已禁言，不会发言" else if (joined) "在群内" else "不在群内")
                                if (joined) GroupActivityLabel(memberActivities[assistant.id]?.content)
                            }
                        },
                        leadingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(checked = joined, onCheckedChange = null)
                                GroupMessageAvatar(
                                    name = assistant.name.ifBlank { "未命名助手" },
                                    avatar = assistant.avatar,
                                    activity = if (joined) memberActivities[assistant.id]?.content ?: "暂无活动记录" else null,
                                )
                            }
                        },
                        trailingContent = {
                            if (joined) {
                                IconButton(onClick = { vm.setMemberMuted(assistant.id, !muted) }) {
                                    Icon(
                                        imageVector = if (muted) HugeIcons.Cancel01 else HugeIcons.VolumeHigh,
                                        contentDescription = if (muted) "解除禁言" else "禁言",
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showSummaryHistory && !isGlobal) {
        BasicAlertDialog(onDismissRequest = { showSummaryHistory = false }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("历史总结", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "仅用于当前群聊，原始消息仍完整保留。展开阶段与主题可查看全文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).weight(1f, fill = false),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (vm.summaryHistoryLoading) {
                            item { Text("正在加载…") }
                        } else if (vm.summaryHistoryError != null) {
                            item { Text(vm.summaryHistoryError.orEmpty(), color = MaterialTheme.colorScheme.error) }
                        } else if (historyStages.isEmpty()) {
                            item { Text("暂无历史总结", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        itemsIndexed(historyStages, key = { _, stage -> stage.id }) { index, stage ->
                            GroupSummaryStageCard(
                                stage = stage,
                                stageNumber = index + 1,
                                valid = stage.id in validHistoryIds,
                                archived = stage.id in archivedHistoryIds,
                            )
                        }
                    }
                    TextButton(onClick = { showSummaryHistory = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { if (!vm.saving) showUnsavedDialog = false },
            title = { Text("未保存的修改") },
            text = { Column {
                Text("设置已修改但未保存，是否保存？")
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !vm.saving, onClick = { save(true) }) { Text("保存") } },
            dismissButton = {
                TextButton(enabled = !vm.saving, onClick = {
                    vm.discard()
                    showUnsavedDialog = false
                    nav.popBackStack()
                }) { Text("放弃") }
            },
        )
    }

    if (showDeleteDialog && current != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除群聊") },
            text = { Text("会一并删除这个群的聊天记录，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OverrideStatus(
    inherited: Boolean,
    onReset: () -> Unit,
    onCustomize: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (inherited) "继承全局默认" else "本群自定义",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = if (inherited) onCustomize else onReset, enabled = enabled) {
            Text(if (inherited) "设为自定义" else "恢复继承")
        }
    }
}

@Composable
private fun StrategyPicker(
    current: GroupActivationStrategy,
    onSelect: (GroupActivationStrategy) -> Unit,
) {
    Column {
        StrategyOption(GroupActivationStrategy.LIST, "按名单轮流", "每个成员按顺序各说一次", current, onSelect)
        StrategyOption(GroupActivationStrategy.NATURAL, "自然接话", "AI 选择本轮首位，其余成员依次接话，每人每轮一次", current, onSelect)
        StrategyOption(GroupActivationStrategy.MANUAL, "手动点名", "所选成员按设置轮数回复，跳过则全员按名单轮流", current, onSelect)
        StrategyOption(GroupActivationStrategy.POOLED, "纯随机", "每轮随机排列全部成员，尽量避免与上一轮末位相同的人开场", current, onSelect)
    }
}

@Composable
private fun StrategyOption(
    strategy: GroupActivationStrategy,
    title: String,
    subtitle: String,
    current: GroupActivationStrategy,
    onSelect: (GroupActivationStrategy) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = current == strategy, onClick = { onSelect(strategy) })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == strategy, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
