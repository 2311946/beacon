package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChat
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel

// TODO(i18n): 对话框内的文案先写中文，后续统一提到 strings.xml
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatListPage(vm: GroupChatListVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsState()
    val groupChats by vm.groupChats.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<GroupChat?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_chat_title)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.GroupChatDefaults) }) {
                        Icon(HugeIcons.Settings03, contentDescription = "群聊全局默认设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(HugeIcons.MessageAdd01, contentDescription = "创建群聊")
            }
        },
    ) { innerPadding ->
        if (groupChats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.group_chat_list_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(groupChats, key = { it.id }) { group ->
                GroupChatItem(
                    group = group,
                    assistants = settings.assistants,
                    onClick = {
                        navController.navigate(Screen.GroupChat(group.id.toString()))
                    },
                    onSettings = {
                        navController.navigate(Screen.GroupChatSettings(group.id.toString()))
                    },
                    onDelete = { groupToDelete = group },
                )
                }
            }
        }

        if (showCreateDialog) {
            CreateGroupChatDialog(
                assistants = settings.assistants,
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, memberIds ->
                    vm.addGroupChat(name, memberIds)
                    showCreateDialog = false
                },
            )
        }
    }
    RikkaConfirmDialog(
        show = groupToDelete != null,
        title = "删除群聊",
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val group = groupToDelete
            groupToDelete = null
            if (group != null) vm.removeGroupChat(group.id)
        },
        onDismiss = { groupToDelete = null },
        text = { Text("确定删除${groupToDelete?.name?.ifBlank { "未命名群聊" }.orEmpty()}群聊吗？删除后聊天记录无法恢复") },
    )
}

@Composable
private fun GroupChatItem(
    group: GroupChat,
    assistants: List<Assistant>,
    onClick: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
) {
    val memberCount = group.memberIds.size
    // 助手可以不起名，用占位名兜底：不能因为名字是空的就把成员整个丢掉
    val memberNames = group.memberIds.map { id ->
        val assistant = assistants.firstOrNull { it.id == id }
        when {
            assistant == null -> "已删除的成员"
            assistant.name.isBlank() -> "未命名助手"
            else -> assistant.name
        }
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            UIAvatar(
                name = group.name.ifBlank { "未命名群聊" },
                value = group.avatar,
                modifier = Modifier.size(48.dp),
            )
        },
        headlineContent = {
            Text(group.name.ifBlank { "未命名群聊" })
        },
        supportingContent = {
            Text(
                text = if (memberCount == 0) {
                    "暂无成员"
                } else {
                    "${memberNames.joinToString("、")}（共 $memberCount 人）"
                }
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onSettings) {
                    Icon(HugeIcons.Settings03, contentDescription = "群聊设置")
                }
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, contentDescription = "删除")
                }
            }
        },
    )
}

@Composable
private fun CreateGroupChatDialog(
    assistants: List<Assistant>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<Uuid>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Uuid>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建群聊") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (selected.isEmpty()) "选择成员" else "选择成员（已选 ${selected.size} 人）",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(assistants, key = { it.id }) { assistant ->
                        val checked = selected.contains(assistant.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 点击交给整行统一处理：再给 Checkbox 传回调会让一次点击被处理两次，选中又被立刻取消
                                .toggleable(
                                    value = checked,
                                    onValueChange = { isChecked ->
                                        if (isChecked) selected.add(assistant.id)
                                        else selected.remove(assistant.id)
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(assistant.name.ifBlank { "未命名助手" })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), selected.toList()) },
                enabled = name.isNotBlank() && selected.isNotEmpty(),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
