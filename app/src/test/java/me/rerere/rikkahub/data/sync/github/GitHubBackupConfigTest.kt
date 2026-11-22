package me.rerere.rikkahub.data.sync.github

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class GitHubBackupConfigTest {
    @Test
    fun `配置toString不显示Token或任何用户输入`() {
        val config = GitHubBackupConfig("private-owner", "private-repository", "private-pat")
        assertFalse(config.toString().contains(config.token))
        assertFalse(config.toString().contains(config.owner))
        assertFalse(config.toString().contains(config.repository))
        assertTrue(config.toString().contains("redacted"))
    }

    @Test
    fun `仓库校验拒绝路径注入查询凭据空值和非法token`() {
        listOf("", "../owner", "owner/repo", "owner?token=secret", "owner@host", "owner\\repo", "-owner", "owner-", "a".repeat(40)).forEach {
            assertTrue(runCatching { GitHubBackupConfig(it, "repo", "pat").validate() }.exceptionOrNull() is GitHubBackupException)
        }
        listOf("", ".", "..", "../repo", "repo/child", "repo?key=secret", "repo#fragment", "repo%2fchild", "repo name", "a".repeat(101)).forEach {
            assertTrue(runCatching { GitHubBackupConfig("owner", it, "pat").validate() }.exceptionOrNull() is GitHubBackupException)
        }
        listOf("", "pat\nheader", "pat\rheader", "pat key", "密钥", "pat\u007f", "a".repeat(1025)).forEach {
            val error = runCatching { GitHubBackupConfig("owner", "repo", it).validate() }.exceptionOrNull()
            assertTrue(error is GitHubBackupException)
            assertFalse(error.toString().contains("header"))
        }
        GitHubBackupConfig("owner-name", ".private_repo.git", "github_pat_test").validate()
    }

    @Test
    fun `Beacon筛选要求真实日期与安全ZIP名称`() {
        listOf("beacon-backup-2026-11-21", "beacon-backup-2024-02-29-123456-random").forEach {
            assertTrue(isBeaconTag(it))
            assertTrue(isBeaconZip("$it.zip"))
        }
        listOf(
            "beacon-backup-2026-02-29", "beacon-backup-2026-13-01", "beacon-backup-today",
            "rikkahub-backup-2026-11-21", "beacon-backup-2026-11-21/../../secret",
            "beacon-backup-2026-11-21?token=secret", "beacon-backup-2026-11-21-",
        ).forEach {
            assertFalse(it, isBeaconTag(it))
            assertFalse(it, isBeaconZip("$it.zip"))
        }
        assertFalse(isBeaconZip("beacon-backup-2026-11-21.txt"))
        assertFalse(isBeaconZip("beacon-backup-2026-11-21.ZIP"))
    }

    @Test
    fun `独立偏好存储读写三字段且不写Settings`() {
        val values = mutableMapOf<String, Any>()
        var applied = 0
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args[0] as String] = args[1] as String
                    editor
                }
                "apply" -> {
                    applied++
                    null
                }
                else -> error("不应调用其他偏好写入接口")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] as String? ?: args[1]
                "getBoolean" -> values[args[0] as String] as Boolean? ?: args[1]
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0] as String)
                "edit" -> editor
                else -> error("不应调用其他偏好读取接口")
            }
        } as SharedPreferences
        val store = GitHubBackupStore(preferences)
        assertEquals("", store.load().token)
        val config = GitHubBackupConfig("owner", "repository", "test-only-pat")
        store.save(config)
        assertEquals(1, applied)
        assertEquals(setOf("owner", "repository", "token"), values.keys)
        val loaded = store.load()
        assertEquals(config.owner, loaded.owner)
        assertEquals(config.repository, loaded.repository)
        assertEquals(config.token, loaded.token)
        assertFalse(loaded.toString().contains(config.token))
    }
}
