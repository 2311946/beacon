package me.rerere.rikkahub.data.sync.github

import java.io.IOException

class GitHubBackupConfig(
    val owner: String = "",
    val repository: String = "",
    val token: String = "",
) {
    override fun toString(): String = "GitHubBackupConfig(token=<redacted>)"

    internal fun validate() {
        if (!OWNER.matches(owner) || !REPOSITORY.matches(repository) || repository in setOf(".", "..")) {
            throw GitHubBackupException("仓库地址无效，请分别填写 GitHub owner 和 repository")
        }
        if (token.isEmpty() || token.length > 1024 || token.any { it.code !in 33..126 }) {
            throw GitHubBackupException("GitHub Token 无效，请填写不含空白的访问令牌")
        }
    }

    private companion object {
        val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        val REPOSITORY = Regex("[A-Za-z0-9._-]{1,100}")
    }
}

data class GitHubBackupItem(
    val assetId: Long,
    val name: String,
    val size: Long,
    val createdAt: String,
    val releaseName: String,
    val tag: String,
    val digest: String? = null,
)

// 仅使用本地产生的提示；不保留请求、响应、Token 或原始异常链。
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubBackupException(message: String) : IOException(message),
    kotlinx.coroutines.CopyableThrowable<GitHubBackupException> {
    // 禁止协程堆栈恢复反射复制并追加 cause；公开错误只保留安全诊断。
    override fun createCopy(): GitHubBackupException? = null
}
