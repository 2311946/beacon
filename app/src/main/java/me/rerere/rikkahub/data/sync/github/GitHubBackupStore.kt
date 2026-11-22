package me.rerere.rikkahub.data.sync.github

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.format.DateTimeParseException

data class GitHubBackupStamp(
    val owner: String,
    val repository: String,
    val createdAt: String,
    val size: Long,
)

class GitHubBackupStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("github_backup", Context.MODE_PRIVATE),
    )

    @Synchronized
    fun load(): GitHubBackupConfig = GitHubBackupConfig(
        owner = preferences.getString("owner", "").orEmpty(),
        repository = preferences.getString("repository", "").orEmpty(),
        token = preferences.getString("token", "").orEmpty(),
    )

    @Synchronized
    fun isConnected(): Boolean = try {
        load().validate()
        preferences.getBoolean("connected", false)
    } catch (_: GitHubBackupException) {
        false
    } catch (_: ClassCastException) {
        false
    }

    @Synchronized
    fun save(config: GitHubBackupConfig) {
        val editor = preferences.edit()
            .putString("owner", config.owner)
            .putString("repository", config.repository)
            .putString("token", config.token)
        if (preferences.getBoolean("connected", false)) {
            editor.remove("connected")
        }
        editor.apply()
    }

    @Synchronized
    fun saveConnected(config: GitHubBackupConfig) {
        config.validate()
        val keys = setOf("owner", "repository", "token", "connected")
        val previous = try {
            preferences.all.filterKeys { it in keys }
        } catch (_: Exception) {
            throw GitHubBackupException("GitHub 连接配置保存失败，请重试")
        }
        val committed = try {
            preferences.edit()
                .putString("owner", config.owner)
                .putString("repository", config.repository)
                .putString("token", config.token)
                .putBoolean("connected", true)
                .commit()
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            try {
                val editor = preferences.edit()
                keys.forEach { key ->
                    when (val value = previous[key]) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                // commit 失败也可能已更新内存，回滚只触及连接配置，保留备份记录。
                editor.commit()
            } catch (_: Exception) {
                // 不传播可能包含 token 的原始异常或回滚异常。
            }
            throw GitHubBackupException("GitHub 连接配置保存失败，请重试")
        }
    }

    @Synchronized
    fun loadLastBackup(config: GitHubBackupConfig): GitHubBackupStamp? {
        try {
            val owner = preferences.getString("last_backup_owner", null) ?: return null
            val repository = preferences.getString("last_backup_repository", null) ?: return null
            if (!owner.equals(config.owner, ignoreCase = true) ||
                !repository.equals(config.repository, ignoreCase = true)
            ) {
                return null
            }
            val createdAt = preferences.getString("last_backup_created_at", null) ?: return null
            val size = preferences.getLong("last_backup_size", -1L)
            if (size < 0) return null
            Instant.parse(createdAt)
            return GitHubBackupStamp(owner, repository, createdAt, size)
        } catch (_: DateTimeParseException) {
            return null
        } catch (_: ClassCastException) {
            return null
        }
    }

    @Synchronized
    fun saveLastBackup(config: GitHubBackupConfig, item: GitHubBackupItem) {
        preferences.edit()
            .putString("last_backup_owner", config.owner)
            .putString("last_backup_repository", config.repository)
            .putString("last_backup_created_at", item.createdAt)
            .putLong("last_backup_size", item.size)
            .apply()
    }
}
