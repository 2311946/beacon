package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerGroup
import me.rerere.rikkahub.data.datastore.StickerStore
import me.rerere.rikkahub.ui.components.ai.StickerGroupTabs
import me.rerere.rikkahub.ui.components.ai.StickerThumbnail
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingStickersPage() {
    val context = LocalContext.current
    val store = remember(context) { StickerStore.get(context) }
    val groups by store.groups.collectAsStateWithLifecycle()
    val storeError by store.error.collectAsStateWithLifecycle()
    val displaySize by store.displaySize.collectAsStateWithLifecycle()
    val sizeScope = rememberCoroutineScope()
    var sizeError by remember { mutableStateOf<String?>(null) }
    var savingSize by remember { mutableStateOf(false) }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var addToGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var batchAddToGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStickerDelete by remember { mutableStateOf<Pair<String, Sticker>?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<StickerGroup?>(null) }
    val group = groups.find { it.id == selectedGroupId } ?: groups.firstOrNull()
    var importGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { uris ->
        val target = importGroupId
        importGroupId = null
        if (uris.isNotEmpty() && target != null) {
            importing = true
            importStatus = null
            sizeScope.launch {
                try {
                    val added = withContext(Dispatchers.IO) { store.importImages(target, uris) }
                    selectedGroupId = target
                    importStatus = "已保存 ${added.size} 张本地表情"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    importStatus = e.message ?: "图片导入失败，请重试"
                } finally {
                    importing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("表情管理") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("表情包大小", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    me.rerere.rikkahub.data.datastore.StickerDisplaySize.entries.forEach { size ->
                        androidx.compose.material3.FilterChip(
                            selected = displaySize == size,
                            enabled = !savingSize,
                            onClick = {
                                savingSize = true
                                sizeError = null
                                sizeScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.setDisplaySize(size) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        sizeError = e.message ?: "表情大小保存失败"
                                    } finally {
                                        savingSize = false
                                    }
                                }
                            },
                            label = { Text(size.label) },
                        )
                    }
                }
                Text("用户与 AI 共用；中号默认最大宽度为可用气泡宽度的50%，最大高度150dp，保持原比例。", style = MaterialTheme.typography.bodySmall)
                sizeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            storeError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showCreateGroup = true }, enabled = storeError == null) {
                    Text("新建分组")
                }
                Button(
                    onClick = { addToGroupId = group?.id },
                    enabled = group != null && storeError == null,
                ) {
                    Text("添加表情")
                }
            }
            TextButton(
                onClick = { batchAddToGroupId = group?.id },
                enabled = group != null && storeError == null,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("批量添加表情")
            }
            TextButton(
                onClick = {
                    importGroupId = group?.id
                    try {
                        photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ))
                    } catch (e: Exception) {
                        importGroupId = null
                        importStatus = e.message ?: "无法打开系统相册"
                    }
                },
                enabled = group != null && storeError == null && !importing && importGroupId == null,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text(if (importing) "保存图片中…" else "从相册选择（可多选）") }
            importStatus?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (group != null) {
                StickerGroupTabs(groups, group.id) { selectedGroupId = it }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${group.stickers.size} 张表情 · 点击或长按可删除",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { pendingGroupDelete = group },
                        enabled = storeError == null,
                    ) {
                        Text("删除分组", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (group == null || group.stickers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (storeError != null) "表情数据不可用，原始数据未被覆盖"
                        else if (group == null) "先创建分组，再从相册选择或通过图片 URL 添加表情"
                        else "此分组还没有表情，可从相册多选或通过图片 URL 添加",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(group.stickers, key = { it.id }) { sticker ->
                        val requestDelete = {
                            if (storeError == null) pendingStickerDelete = group.id to sticker
                        }
                        StickerThumbnail(
                            sticker = sticker,
                            onClick = requestDelete,
                            clickLabel = "删除表情",
                            onLongClick = requestDelete,
                        )
                    }
                }
            }
        }
    }

    if (showCreateGroup) {
        StickerActionDialog(
            title = "新建表情分组",
            message = "为这组表情输入一个名称。",
            inputLabel = "分组名称",
            confirmLabel = "创建",
            onDismiss = { showCreateGroup = false },
            onConfirm = { name ->
                val created = withContext(Dispatchers.IO) { store.addGroup(name) }
                selectedGroupId = created.id
            },
        )
    }
    addToGroupId?.let { groupId ->
        StickerActionDialog(
            title = "添加 URL 表情",
            message = "请输入可直接访问的 HTTP 或 HTTPS 图片地址。",
            inputLabel = "图片 URL",
            keyboardType = KeyboardType.Uri,
            confirmLabel = "添加",
            onDismiss = { addToGroupId = null },
            onConfirm = { url ->
                withContext(Dispatchers.IO) { store.addSticker(groupId, url) }
            },
        )
    }
    batchAddToGroupId?.let { groupId ->
        StickerActionDialog(
            title = "批量添加 URL 表情",
            message = "每行输入一个 HTTP 或 HTTPS 图片地址，空行会忽略。全部地址校验通过后，一次加入当前分组。",
            inputLabel = "图片 URL（每行一个）",
            singleLine = false,
            minLines = 8,
            maxLines = 12,
            confirmLabel = "全部添加",
            onDismiss = { batchAddToGroupId = null },
            onConfirm = { urls ->
                withContext(Dispatchers.IO) { store.addStickers(groupId, urls) }
            },
        )
    }
    pendingStickerDelete?.let { (groupId, sticker) ->
        StickerActionDialog(
            title = "删除这张表情？",
            message = "此操作仅从分组移除表情，不会删除远端图片。",
            confirmLabel = "删除",
            onDismiss = { pendingStickerDelete = null },
            onConfirm = { withContext(Dispatchers.IO) { store.removeSticker(groupId, sticker.id) } },
        )
    }
    pendingGroupDelete?.let { target ->
        StickerActionDialog(
            title = "删除整个分组？",
            message = "将删除“${target.name}”分组及其中的全部表情，此操作不可撤销。",
            confirmLabel = "删除分组",
            onDismiss = { pendingGroupDelete = null },
            onConfirm = { withContext(Dispatchers.IO) { store.deleteGroup(target.id) } },
        )
    }
}

@Composable
private fun StickerActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
    inputLabel: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message)
                if (inputLabel != null) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        label = { Text(inputLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = singleLine,
                        minLines = minLines,
                        maxLines = maxLines,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (inputLabel == null || input.isNotBlank()),
                onClick = {
                    if (!busy) {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                onConfirm(input)
                                onDismiss()
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                error = exception.message ?: "操作失败，请重试"
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            ) {
                Text(if (busy) "保存中" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
