package me.rerere.rikkahub.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.CrashHandler
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

internal class SafeModeBackupViewModel(
    private val context: Context,
    private val backupManager: BackupManager,
) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var restartRequired by mutableStateOf(backupManager.hasPendingRestore())
        private set

    fun export(uri: Uri) {
        if (busy || restartRequired) return
        busy = true
        status = "正在创建完整备份…"
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var completed = false
            try {
                exportBackup(uri)
                completed = true
                status = "备份导出完成。"
            } catch (cancelled: CancellationException) {
                status = "备份导出已取消。"
                throw cancelled
            } catch (error: Exception) {
                status = "备份导出失败：${error.message ?: "未知错误"}"
            } finally {
                if (!completed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } catch (_: Exception) {
                            // 文档提供方不一定支持删除；保留原始错误，界面提示导出未完成。
                        }
                    }
                }
                busy = false
            }
        }
    }

    fun restore(uri: Uri) {
        if (busy || restartRequired) return
        busy = true
        status = "正在校验并暂存备份，尚未覆盖当前数据…"
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                importBackup(uri)
            } catch (cancelled: CancellationException) {
                if (!restartRequired) status = "备份导入已取消。"
                throw cancelled
            } catch (error: Exception) {
                status = "备份导入失败：${error.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        var archive: File? = null
        try {
            // 先接管临时文件，再响应取消，避免 IO 调度返回时丢失待删除的 ZIP。
            archive = withContext(NonCancellable) {
                backupManager.createRescueBackup()
            }
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                checkNotNull(output) { "无法打开导出文件" }
                archive.inputStream().use { input -> copyBackup(input, output) }
            }
            currentCoroutineContext().ensureActive()
        } finally {
            archive?.delete()
        }
    }

    private suspend fun importBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("safe-mode-import-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法打开备份文件" }
                archive.outputStream().use { output -> copyBackup(input, output) }
            }
            currentCoroutineContext().ensureActive()
            // 发布与重启标记必须一起完成，不能在切回界面时丢失已发布的恢复。
            withContext(NonCancellable) {
                backupManager.stageRestore(archive, includeDatabase = true, includeFiles = true)
                restartRequired = true
                status = "备份已暂存。请退出并重新打开应用以完成恢复。"
            }
        } finally {
            archive.delete()
        }
    }

    private suspend fun copyBackup(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
}

class SafeModeActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()
    private val backupManager by inject<BackupManager>()
    private val backupState by viewModels<SafeModeBackupViewModel> {
        viewModelFactory {
            initializer { SafeModeBackupViewModel(applicationContext, backupManager) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = CrashHandler.getStackTrace(this)
        CrashHandler.clearCrashed(this)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
                var showAssistantPicker by remember { mutableStateOf(false) }
                var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                val busy = backupState.busy
                var pickingDocument by rememberSaveable { mutableStateOf(false) }
                val status = backupState.status
                val restartRequired = backupState.restartRequired
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    pickingDocument = false
                    if (uri != null) backupState.export(uri)
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    pickingDocument = false
                    if (uri != null && !busy && !restartRequired) importUri = uri
                }
                val actionsEnabled = !busy && !pickingDocument && !restartRequired && importUri == null
                BackHandler(enabled = busy || restartRequired) {}

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.safe_mode_title)) })
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.safe_mode_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = stringResource(
                                R.string.safe_mode_current_assistant,
                                settings.getCurrentAssistant().name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) }),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        Button(
                            onClick = { showAssistantPicker = true },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_switch_assistant))
                        }

                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(this@SafeModeActivity, RouteActivity::class.java))
                                finish()
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_enter_app))
                        }

                        OutlinedButton(
                            onClick = {
                                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                try {
                                    pickingDocument = true
                                    exportLauncher.launch("${BackupManager.BACKUP_FILE_PREFIX}$timestamp.zip")
                                } catch (error: Exception) {
                                    pickingDocument = false
                                    backupState.status = "无法选择导出位置：${error.message ?: "未知错误"}"
                                }
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("导出完整备份")
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    pickingDocument = true
                                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                } catch (error: Exception) {
                                    pickingDocument = false
                                    backupState.status = "无法选择备份文件：${error.message ?: "未知错误"}"
                                }
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("导入备份")
                        }

                        if (busy) CircularProgressIndicator()
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)

                        if (stackTrace != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.safe_mode_crash_report),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("crash", stackTrace))
                                    }
                                ) {
                                    Text(stringResource(R.string.safe_mode_copy))
                                }
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                val vScroll = rememberScrollState()
                                val hScroll = rememberScrollState()
                                Text(
                                    text = stackTrace,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .verticalScroll(vScroll)
                                        .horizontalScroll(hScroll),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                importUri?.let { uri ->
                    AlertDialog(
                        onDismissRequest = { importUri = null },
                        title = { Text("确认覆盖并恢复备份？") },
                        text = {
                            Text("备份中的设置、偏好、数据库和同名附件将覆盖当前数据。建议先导出备份。校验成功后需退出并重新打开应用，完整重启进程后才会应用恢复；仅重新打开此页面无效。")
                        },
                        confirmButton = {
                            Button(onClick = {
                                importUri = null
                                backupState.restore(uri)
                            }) { Text("确认覆盖") }
                        },
                        dismissButton = {
                            TextButton(onClick = { importUri = null }) { Text("取消") }
                        },
                    )
                }
                if (restartRequired) BackupDialog()

                if (showAssistantPicker) {
                    AssistantPickerSheet(
                        settings = settings,
                        onAssistantSelected = { assistantId ->
                            scope.launch { settingsStore.selectAssistant(assistantId) }
                            context.writeStringPreference("lastConversationId", null)
                            showAssistantPicker = false
                        },
                        onDismiss = { showAssistantPicker = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    onAssistantSelected: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val filteredAssistants = remember(settings.assistants, selectedTagIds) {
        if (selectedTagIds.isEmpty()) settings.assistants
        else settings.assistants.filter { it.tags.any { id -> id in selectedTagIds } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_assistants),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { it.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == settings.assistantId
                    Card(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onAssistantSelected(assistant.id)
                            }
                        },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
