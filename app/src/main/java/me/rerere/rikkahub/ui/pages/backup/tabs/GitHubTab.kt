package me.rerere.rikkahub.ui.pages.backup.tabs

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.sync.github.GitHubBackupConfig
import me.rerere.rikkahub.data.sync.github.GitHubBackupItem
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

@Composable
fun GitHubTab(vm: BackupVM) {
    val savedConfig by vm.githubConfig.collectAsStateWithLifecycle()
    val connected by vm.githubConnected.collectAsStateWithLifecycle()
    val connecting by vm.githubConnecting.collectAsStateWithLifecycle()
    val busy by vm.githubBusy.collectAsStateWithLifecycle()
    val status by vm.githubStatus.collectAsStateWithLifecycle()
    val backupItems by vm.githubBackupItems.collectAsStateWithLifecycle()
    val restoreReady by vm.githubRestoreReady.collectAsStateWithLifecycle()
    val lastBackup by vm.githubLastBackup.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var showConfiguration by rememberSaveable { mutableStateOf(false) }
    var linkError by remember(vm, savedConfig) { mutableStateOf<String?>(null) }
    var includeDatabase by remember(vm) { mutableStateOf(true) }
    var includeFiles by remember(vm) { mutableStateOf(true) }
    var selectedItem by remember(vm, savedConfig) { mutableStateOf<GitHubBackupItem?>(null) }
    var historyRequested by remember(vm, savedConfig) { mutableStateOf(false) }
    var stateBeforeRefresh by remember(vm, savedConfig) {
        mutableStateOf<UiState<List<GitHubBackupItem>>>(UiState.Idle)
    }
    val releasesUrl = remember(savedConfig) {
        runCatching {
            savedConfig.validate()
            Uri.Builder()
                .scheme("https")
                .authority("github.com")
                .appendPath(savedConfig.owner)
                .appendPath(savedConfig.repository)
                .appendPath("releases")
                .build()
                .toString()
        }.getOrNull()
    }
    val configComplete = releasesUrl != null
    val enabled = !busy && !connecting && !restoreReady
    // 配置一变立即隐藏旧列表；刷新启动但尚未发出 Loading 时也不显示旧结果。
    val historyVisible = historyRequested && backupItems !== stateBeforeRefresh

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup(title = { Text("GitHub 备份") }) {
            item(
                headlineContent = {
                    Text(if (connected) "已连接 GitHub (@${savedConfig.owner})" else "尚未连接 GitHub")
                },
                supportingContent = {
                    Text(
                        if (savedConfig.owner.isNotBlank() && savedConfig.repository.isNotBlank()) {
                            "当前仓库：${savedConfig.owner}/${savedConfig.repository}"
                        } else {
                            "连接后自动创建本账号的私有仓库 beacon-backup，使用 Releases 保存备份。"
                        }
                    )
                },
            )
            if (connected && releasesUrl != null) {
                formItem {
                    FilledTonalButton(
                        onClick = {
                            linkError = null
                            runCatching { uriHandler.openUri(releasesUrl) }.onFailure {
                                linkError = "无法打开 GitHub，请检查是否安装了可用浏览器后重试。"
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("在 GitHub 上查看备份")
                    }
                }
            }
            formItem {
                FilledTonalButton(
                    onClick = { showConfiguration = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (connected) "重新配置 GitHub" else "配置 GitHub 备份")
                }
                if (!connected && configComplete) {
                    FilledTonalButton(
                        onClick = { vm.verifySavedGitHub() },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("验证并继续使用原仓库") }
                    Text("保留 ${savedConfig.owner}/${savedConfig.repository}，不会改建到 beacon-backup。")
                }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (connected) {
                val stamp = lastBackup?.takeIf {
                    it.owner.equals(savedConfig.owner, ignoreCase = true) &&
                        it.repository.equals(savedConfig.repository, ignoreCase = true)
                }
                item(
                    headlineContent = { Text("上次备份") },
                    supportingContent = {
                        if (stamp == null) {
                            Text("暂无当前仓库的备份记录。")
                        } else {
                            val date = remember(stamp.createdAt) {
                                runCatching {
                                    Instant.parse(stamp.createdAt).toLocalDateTime()
                                }.getOrDefault(stamp.createdAt)
                            }
                            Text("$date · ${stamp.size.fileSizeToString()}")
                        }
                    },
                )
            }
        }

        CardGroup(title = { Text("隐私与凭据") }) {
            item(
                headlineContent = { Text("请妥善保管 Token 和备份") },
                supportingContent = {
                    Text(
                        "Token 仅保存在本机，不进入 BeaconZIP，也不进入系统备份。" +
                            "备份始终包含设置，其中可能有 API 密钥等敏感配置；ZIP 未加密，" +
                            "请勿上传到公开仓库，也不要随意分享备份。"
                    )
                },
            )
        }

        CardGroup(title = { Text("备份 / 恢复内容") }) {
            item(
                headlineContent = { Text("设置（始终包含）") },
                supportingContent = { Text("取消以下两项时，仅备份或恢复设置。") },
            )
            switchItem(
                checked = includeDatabase,
                onCheckedChange = { includeDatabase = it },
                enabled = enabled,
                headlineContent = { Text("数据库 / 聊天记录") },
            )
            switchItem(
                checked = includeFiles,
                onCheckedChange = { includeFiles = it },
                enabled = enabled,
                headlineContent = { Text("附件 / 文件") },
            )
        }

        if (connected) {
            CardGroup(title = { Text("云端备份 / 恢复") }) {
                formItem {
                    Button(
                        onClick = {
                            historyRequested = false
                            selectedItem = null
                            vm.backupToGitHub(savedConfig, includeDatabase, includeFiles)
                        },
                        enabled = enabled && configComplete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("备份到云端")
                    }
                    FilledTonalButton(
                        onClick = {
                            selectedItem = null
                            stateBeforeRefresh = vm.githubBackupItems.value
                            historyRequested = true
                            vm.loadGitHubBackupItems(savedConfig)
                        },
                        enabled = enabled && configComplete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("从云端恢复")
                    }
                    Text(
                        "每次备份创建新版本，不覆盖旧版本。点击从云端恢复会刷新历史列表，" +
                            "选择版本并确认后才会覆盖本机数据。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if ((busy || !status.isNullOrBlank()) && !showConfiguration) {
            CardGroup(title = { Text("操作状态") }) {
                formItem {
                    if (busy || connecting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在处理，请勿重复操作…")
                    }
                    status?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            }
        }

        if (connected && configComplete && historyRequested) {
            CardGroup(title = { Text("历史备份") }) {
                item(
                    headlineContent = { Text("${savedConfig.owner}/${savedConfig.repository}") },
                    supportingContent = { Text("点击从云端恢复可重新刷新当前仓库的历史备份。") },
                )
                if (!historyVisible) {
                    item(headlineContent = { Text("等待当前仓库的列表结果…") })
                } else {
                    when (val state = backupItems) {
                        UiState.Idle -> item(headlineContent = { Text("尚未加载，请刷新重试。") })
                        UiState.Loading -> formItem {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在读取历史备份…")
                        }
                        is UiState.Error -> item(
                            headlineContent = { Text("历史备份加载失败") },
                            supportingContent = {
                                Text(
                                    state.error.message ?: "未知错误，请刷新重试。",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                        is UiState.Success -> {
                            if (state.data.isEmpty()) {
                                item(headlineContent = { Text("当前仓库暂无备份。") })
                            }
                            state.data.forEach { item ->
                                formItem(
                                    headlineContent = { Text(item.name) },
                                    supportingContent = {
                                        val date = remember(item.createdAt) {
                                            runCatching {
                                                Instant.parse(item.createdAt).toLocalDateTime()
                                            }.getOrDefault(item.createdAt)
                                        }
                                        Text("$date · ${item.size.fileSizeToString()}")
                                    },
                                ) {
                                    Text(
                                        "${item.releaseName} · ${item.tag}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    FilledTonalButton(
                                        onClick = { selectedItem = item },
                                        enabled = enabled,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("恢复此版本…")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfiguration) {
        GitHubConnectionDialog(
            vm = vm,
            savedConfig = savedConfig,
            connected = connected,
            connecting = connecting,
            busy = busy,
            restoreReady = restoreReady,
            status = status,
            onDismiss = { showConfiguration = false },
        )
    }

    selectedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { if (enabled) selectedItem = null },
            title = { Text("确认覆盖当前数据？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("将从 ${savedConfig.owner}/${savedConfig.repository} 恢复：${item.name}")
                    Text(
                        "这会覆盖当前所选数据：设置（始终恢复）" +
                            (if (includeDatabase) "、数据库 / 聊天记录" else "") +
                            (if (includeFiles) "、附件 / 文件" else "") + "。"
                    )
                    Text("建议先备份当前数据，再恢复任意历史版本。恢复成功后需要重启应用。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!vm.githubBusy.value && !vm.githubConnecting.value && historyVisible &&
                            vm.githubConfig.value === savedConfig
                        ) {
                            selectedItem = null
                            vm.restoreFromGitHub(savedConfig, item, includeDatabase, includeFiles)
                        }
                    },
                    enabled = enabled && connected && configComplete && historyVisible,
                ) {
                    Text("覆盖并恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedItem = null },
                    enabled = enabled,
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
private fun GitHubConnectionDialog(
    vm: BackupVM,
    savedConfig: GitHubBackupConfig,
    connected: Boolean,
    connecting: Boolean,
    busy: Boolean,
    restoreReady: Boolean,
    status: String?,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // 令牌草稿仅在当前弹窗内存中保留，不恢复、不预填已保存的凭据。
    var token by remember(vm) { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var configBeforeAttempt by remember(vm) { mutableStateOf(savedConfig) }
    var linkError by remember(vm) { mutableStateOf<String?>(null) }
    val locked = connecting || busy || restoreReady
    // 只有本次连接发布了新配置才算成功，避免失败后将保留的旧连接误报为成功。
    val succeeded = attempted && !locked && connected && savedConfig.repository == "beacon-backup" &&
        status == "✅ 已连接 GitHub (@${savedConfig.owner})" &&
        (token.isEmpty() || (savedConfig !== configBeforeAttempt && savedConfig.token == token.trim()))

    AlertDialog(
        onDismissRequest = { if (!locked) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !locked,
            dismissOnClickOutside = !locked,
        ),
        title = { Text("GitHub 备份") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connecting || busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在验证 Token 并连接私有仓库，请稍候…")
                }
                if (succeeded) {
                    Text("✅ 已连接 GitHub (@${savedConfig.owner})")
                } else if (attempted) {
                    status?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("① 去 GitHub 创建 Token", style = MaterialTheme.typography.titleSmallEmphasized)
                FilledTonalButton(
                    onClick = {
                        linkError = null
                        runCatching {
                            uriHandler.openUri("https://github.com/settings/personal-access-tokens/new")
                        }.onFailure {
                            linkError = "无法打开 GitHub，请检查是否安装了可用浏览器后重试。"
                        }
                    },
                    enabled = !locked && !succeeded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("去 GitHub 创建 Token")
                }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "创建 fine-grained Token，Resource owner 选择本人。将 Expiration 改为 " +
                        "No expiration；如账号策略不提供此选项，请选择允许的期限，到期后重新连接。"
                )
                Text(
                    "自动建库：Repository access 选择 All repositories，" +
                        "Repository permissions 设置 Administration: Read and write（自动创建私有仓库），" +
                        "以及 Contents: Read and write（上传和读取 Releases 备份）。"
                )
                Text(
                    "不想授权 All repositories：先手动创建本人名下的 private 仓库 beacon-backup，" +
                        "再选 Only select repositories，仅选择该库并授予 Contents: Read and write。" +
                        "已有私有仓库不需要自动建库权限；应用不会自动收窄 Token 的授权范围。"
                )
                Text(
                    "长期 Token 泄露后可在撤销前持续访问授权仓库；All repositories 的读写及管理权限" +
                        "影响范围更广。请勿分享 Token，定期检查授权，不再使用或怀疑泄露时及时撤销。",
                    color = MaterialTheme.colorScheme.error,
                )
                Text("② 复制并粘贴 Token", style = MaterialTheme.typography.titleSmallEmphasized)
                CompositionLocalProvider(
                    LocalAutofill provides null,
                    LocalAutofillManager provides null,
                ) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = {
                            token = it
                            attempted = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !locked && !succeeded,
                        singleLine = true,
                        label = { Text("访问令牌（Token）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        supportingText = { Text("始终遮罩，可粘贴，不启用自动填充。") },
                    )
                }
                Text("③ 测试并连接", style = MaterialTheme.typography.titleSmallEmphasized)
                Text("验证 Token 后，将自动创建或连接本账号的私有仓库 beacon-backup。")
                if (savedConfig.owner.isNotBlank() && savedConfig.repository.isNotBlank()) {
                    Text(
                        "已有配置：${savedConfig.owner}/${savedConfig.repository}。" +
                            "重新连接成功后将改为本次 Token 所属账号的 beacon-backup；" +
                            "失败不会清除旧连接，也不会删除旧仓库和备份。"
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss, enabled = !locked) {
                    Text("完成")
                }
            } else {
                TextButton(
                    onClick = {
                        if (!vm.githubBusy.value && !vm.githubConnecting.value && !vm.githubRestoreReady.value) {
                            linkError = null
                            configBeforeAttempt = vm.githubConfig.value
                            attempted = true
                            vm.connectGitHub(token.trim())
                        }
                    },
                    enabled = !locked && token.isNotBlank(),
                ) {
                    Text("测试并连接")
                }
            }
        },
        dismissButton = {
            if (!succeeded) {
                TextButton(onClick = onDismiss, enabled = !locked) {
                    Text("取消")
                }
            }
        },
    )
}
