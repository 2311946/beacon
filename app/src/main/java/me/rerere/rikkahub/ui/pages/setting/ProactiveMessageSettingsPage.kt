package me.rerere.rikkahub.ui.pages.setting

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.ui.context.LocalNavController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveMessageSettingsPage(vm: ProactiveMessageSettingsVM = koinViewModel()) {
    val settings by vm.settings.collectAsState()
    val config = vm.draft
    val busy = vm.saving || vm.savingPrompt
    val editable = vm.ready && !busy
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    val nav = LocalNavController.current
    val context = LocalContext.current
    val exit = {
        if (!busy) {
            if (vm.dirty || vm.promptDirty) showUnsaved = true else nav.popBackStack()
        }
        Unit
    }
    val savePrompt: () -> Unit = {
        vm.savePrompt { Toast.makeText(context, "已保存提示词", Toast.LENGTH_SHORT).show() }
    }
    val save: (Boolean) -> Unit = { leave ->
        val finish = {
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            showUnsaved = false
            if (leave) nav.popBackStack()
            Unit
        }
        val saveRemaining: () -> Unit = {
            if (leave && vm.promptDirty) vm.savePrompt(finish) else finish()
        }
        if (vm.dirty) vm.save(saveRemaining) else saveRemaining()
    }
    BackHandler(enabled = vm.dirty || vm.promptDirty || busy) { exit() }
    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { if (!busy) showUnsaved = false },
            title = { Text("未保存的修改") },
            text = { Column {
                Text(if (vm.savePending) "上次保存未完全成功，是否重试？已应用的设置不会因离开页面而撤销。" else "有未保存的更改，是否保存？")
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                vm.promptError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { save(true) }) { Text("保存") } },
            dismissButton = { TextButton(enabled = !busy, onClick = {
                vm.discard()
                vm.discardPrompt()
                showUnsaved = false
                nav.popBackStack()
            }) { Text(if (vm.savePending) "放弃未提交修改并离开" else "放弃") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("自主唤醒") }, navigationIcon = {
            IconButton(onClick = exit, enabled = !busy) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
            }
        })
    }, bottomBar = {
        Surface {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    if (vm.savePending) "保存未完成，请重试" else if (vm.dirty) "有未保存的修改" else "设置未修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { save(false) },
                    enabled = editable && vm.dirty,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.saving) "保存中…" else "保存") }
            }
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("用户空闲达到设定时长后，角色才会判断是否联系你、休眠或通过 do_task + 任务描述执行当前会话已启用的 MCP 工具。工具审批沿用正常聊天流程，批准前不会执行；没有可用工具时回退为普通联系。默认模板包含任务与授权说明，自定义模板会保留。实际唤醒时间受系统省电策略影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                ListItem(
                    headlineContent = { Text("启用自主唤醒") },
                    supportingContent = { Text("修改后点击底部保存生效；关闭并保存后取消后台唤醒") },
                    trailingContent = { Switch(config.enabled, onCheckedChange = vm::setEnabled, enabled = editable) },
                )
            }
            item {
                Text("空闲多久后唤醒", style = MaterialTheme.typography.titleMedium)
                Text("普通对话从角色回复结束后计时；唤醒后等待你回复，再重新计时。到期检查并每15分钟兜底检查，系统可能延迟执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(selected = config.intervalMinutes == minutes, enabled = editable,
                            onClick = { vm.setInterval(minutes) }, label = { Text("$minutes 分钟") })
                    }
                }
            }
            item {
                Text("允许唤醒的时间窗口", style = MaterialTheme.typography.titleMedium)
                Text("开始与结束相同时为全天；结束早于开始时跨午夜。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = config.startHour, onValueChange = vm::setStartHour,
                        enabled = editable, label = { Text("开始时") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = config.startMinute, onValueChange = vm::setStartMinute,
                        enabled = editable, label = { Text("分") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = config.endHour, onValueChange = vm::setEndHour,
                        enabled = editable, label = { Text("结束时") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = config.endMinute, onValueChange = vm::setEndMinute,
                        enabled = editable, label = { Text("分") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item { Text("启用的角色", style = MaterialTheme.typography.titleMedium) }
            items(settings.assistants, key = { it.id.toString() }) { assistant ->
                ListItem(
                    headlineContent = { Text(assistant.name.ifBlank { "未命名角色" }) },
                    trailingContent = { Checkbox(checked = assistant.id in config.assistantIds, enabled = editable,
                        onCheckedChange = { vm.toggleAssistant(assistant.id) }) },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("决策提示词", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = vm.promptDraft, onValueChange = vm::editPrompt,
                        enabled = vm.promptReady && !busy,
                        label = { Text("醒来后如何思考") },
                        supportingText = { Text("空白时使用默认提示词；修改后保存于本机。") },
                        minLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("支持 {{char_name}}、{{user_name}}、{{time}}、{{idle_time}}；发送决策前自动替换。", style = MaterialTheme.typography.bodySmall)
                    if (vm.promptDirty) Text("提示词有未保存的修改", style = MaterialTheme.typography.bodySmall)
                    vm.promptError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = vm.promptReady && !busy, onClick = savePrompt) {
                        Text(if (vm.savingPrompt) "保存中…" else "保存提示词")
                    }
                }
            }
        }
    }
}
