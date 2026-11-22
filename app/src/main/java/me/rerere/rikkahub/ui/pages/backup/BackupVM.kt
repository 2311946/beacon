package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.sync.github.GitHubBackupStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.sync.github.GitHubBackupConfig
import me.rerere.rikkahub.data.sync.github.GitHubBackupException
import me.rerere.rikkahub.data.sync.github.GitHubBackupItem
import me.rerere.rikkahub.data.sync.github.GitHubBackupStore
import me.rerere.rikkahub.data.sync.github.GitHubSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val githubStore: GitHubBackupStore,
    private val githubSync: GitHubSync,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings {
            it.copy(
                providers = importProviders + it.providers,
            )
        }
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private val _githubConfig = MutableStateFlow(githubStore.load())
    val githubConfig = _githubConfig.asStateFlow()
    private val _githubConnected = MutableStateFlow(githubStore.isConnected())
    val githubConnected = _githubConnected.asStateFlow()
    private val _githubConnecting = MutableStateFlow(false)
    val githubConnecting = _githubConnecting.asStateFlow()
    private val _githubLastBackup = MutableStateFlow<GitHubBackupStamp?>(githubStore.loadLastBackup(_githubConfig.value))
    val githubLastBackup = _githubLastBackup.asStateFlow()
    private val _githubBusy = MutableStateFlow(false)
    val githubBusy = _githubBusy.asStateFlow()
    private val _githubStatus = MutableStateFlow<String?>(null)
    val githubStatus = _githubStatus.asStateFlow()
    private val _githubBackupItems = MutableStateFlow<UiState<List<GitHubBackupItem>>>(UiState.Idle)
    val githubBackupItems = _githubBackupItems.asStateFlow()
    private val _githubRestoreReady = MutableStateFlow(false)
    val githubRestoreReady = _githubRestoreReady.asStateFlow()
    private var githubListedConfig: GitHubBackupConfig? = null

    fun verifySavedGitHub() = githubOperation("已验证并保留原仓库连接；上传写权限由实际备份检查") {
        val config = _githubConfig.value
        githubSync.test(config)
        withContext(NonCancellable + Dispatchers.IO) {
            githubStore.saveConnected(config)
            _githubConnected.value = true
        }
    }

    fun connectGitHub(token: String) = githubOperation(success = null, connecting = true) {
        val config = githubSync.connect(token)
        // 成功后才替换本机旧连接；短暂的本地提交不可取消，避免只保存却未更新界面。
        withContext(NonCancellable + Dispatchers.IO) {
            githubStore.saveConnected(config)
            githubListedConfig = null
            _githubBackupItems.value = UiState.Idle
            _githubLastBackup.value = githubStore.loadLastBackup(config)
            _githubConfig.value = config
            _githubConnected.value = true
            _githubStatus.value = "✅ 已连接 GitHub (@${config.owner})"
        }
    }

    fun backupToGitHub(config: GitHubBackupConfig, includeDatabase: Boolean, includeFiles: Boolean) =
        githubOperation("GitHub 新版本备份上传成功，请刷新历史列表") {
            val item = githubSync.backup(config, includeDatabase, includeFiles)
            withContext(NonCancellable + Dispatchers.IO) {
                githubStore.saveLastBackup(config, item)
                _githubLastBackup.value = githubStore.loadLastBackup(config)
                recordBackupTime()
            }
        }

    fun loadGitHubBackupItems(config: GitHubBackupConfig) = githubOperation("历史备份已刷新") {
        githubListedConfig = null
        _githubBackupItems.value = UiState.Loading
        try {
            val items = githubSync.list(config)
            githubListedConfig = config
            _githubBackupItems.value = UiState.Success(items)
        } catch (cancelled: CancellationException) {
            _githubBackupItems.value = UiState.Idle
            throw cancelled
        } catch (error: Exception) {
            _githubBackupItems.value = UiState.Error(githubUiError(error))
            throw error
        }
    }

    fun restoreFromGitHub(
        config: GitHubBackupConfig,
        item: GitHubBackupItem,
        includeDatabase: Boolean,
        includeFiles: Boolean,
    ) = githubOperation("备份已校验并暂存，请重启应用完成恢复") {
        val listed = githubListedConfig
        val items = (_githubBackupItems.value as? UiState.Success)?.data.orEmpty()
        if (listed == null || listed.owner != config.owner || listed.repository != config.repository ||
            listed.token != config.token || item !in items
        ) {
            throw GitHubBackupException("配置或历史列表已变化，请重新刷新再恢复")
        }
        githubSync.restore(config, item, includeDatabase, includeFiles) {
            _githubRestoreReady.value = true
            _githubStatus.value = "备份已校验并暂存，请重启应用完成恢复"
        }
    }

    // 占用状态在 launch 前设置，防止快速连续点击启动多个远端任务。
    private fun githubOperation(success: String?, connecting: Boolean = false, block: suspend () -> Unit) {
        if (_githubBusy.value || _githubRestoreReady.value) return
        _githubConnecting.value = connecting
        _githubBusy.value = true
        _githubStatus.value = null
        viewModelScope.launch {
            try {
                block()
                if (success != null) _githubStatus.value = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _githubStatus.value = githubUiError(error).message
            } finally {
                _githubBusy.value = false
                _githubConnecting.value = false
            }
        }
    }

    private fun githubUiError(error: Exception): GitHubBackupException =
        error as? GitHubBackupException ?: GitHubBackupException("GitHub 备份操作失败，请检查网络、配置或本地存储")

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
