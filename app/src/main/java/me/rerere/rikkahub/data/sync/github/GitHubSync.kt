package me.rerere.rikkahub.data.sync.github

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.BackupManager
import java.io.File

class GitHubSync(
    private val backupManager: BackupManager,
    context: Context,
    private val client: GitHubReleaseClient,
) {
    private val cacheDir = context.applicationContext.cacheDir

    suspend fun connect(token: String): GitHubBackupConfig = client.connect(token)

    suspend fun test(config: GitHubBackupConfig) {
        client.test(config)
    }

    suspend fun list(config: GitHubBackupConfig): List<GitHubBackupItem> = client.list(config)

    suspend fun backup(
        config: GitHubBackupConfig,
        includeDatabase: Boolean = true,
        includeFiles: Boolean = true,
    ): GitHubBackupItem = safeGitHubOperation {
        // 先确认私库可访问，再创建包含敏感数据的本地 ZIP。
        client.test(config)
        val archive = withContext(NonCancellable) {
            backupManager.createBackup(includeDatabase, includeFiles)
        }
        try {
            currentCoroutineContext().ensureActive()
            client.upload(config, archive)
        } finally {
            archive.delete()
        }
    }

    suspend fun restore(
        config: GitHubBackupConfig,
        item: GitHubBackupItem,
        includeDatabase: Boolean = true,
        includeFiles: Boolean = true,
        onStaged: suspend () -> Unit = {},
    ): Unit = safeGitHubOperation {
        withDownloadedBackup(client, config, item, cacheDir) { archive ->
            withContext(NonCancellable) {
                backupManager.stageRestore(archive, includeDatabase, includeFiles)
                onStaged()
            }
        }
    }
}

internal suspend fun <T> withDownloadedBackup(
    client: GitHubReleaseClient,
    config: GitHubBackupConfig,
    item: GitHubBackupItem,
    directory: File,
    consume: suspend (File) -> T,
): T {
    validateDownloadSize(item.size)
    checkDiskSpace(directory, item.size)
    val archive = File.createTempFile("github-backup-", ".zip", directory)
    try {
        client.download(config, item, archive)
        currentCoroutineContext().ensureActive()
        return consume(archive)
    } finally {
        archive.delete()
    }
}
