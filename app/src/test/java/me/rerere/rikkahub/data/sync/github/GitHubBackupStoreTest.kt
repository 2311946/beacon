package me.rerere.rikkahub.data.sync.github

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

class GitHubBackupStoreTest {
    @Test
    fun allStoreReadAndWriteMethodsAreSynchronized() {
        val names = setOf("load", "isConnected", "save", "saveConnected", "loadLastBackup", "saveLastBackup")
        val methods = GitHubBackupStore::class.java.declaredMethods.filter { it.name in names }

        assertEquals(names, methods.map { it.name }.toSet())
        methods.forEach { method ->
            assertTrue("${method.name} 必须持有同步锁", Modifier.isSynchronized(method.modifiers))
        }
    }

    @Test
    fun loadDefaultsToEmptyFieldsWithoutWritingPreferences() {
        val preferences = MemoryPreferences()

        val loaded = GitHubBackupStore(preferences).load()

        assertEquals("", loaded.owner)
        assertEquals("", loaded.repository)
        assertEquals("", loaded.token)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun saveReloadsAllFieldsFromANewStoreInstance() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val configs = listOf(
            GitHubBackupConfig("first-owner", "first-repository", "first-test-token"),
            GitHubBackupConfig("second-owner", "second-repository", "second-test-token"),
        )

        configs.forEach { config ->
            store.save(config)

            val loaded = GitHubBackupStore(preferences).load()
            assertEquals(config.owner, loaded.owner)
            assertEquals(config.repository, loaded.repository)
            assertEquals(config.token, loaded.token)
        }
    }

    @Test
    fun savingEmptyTokenClearsPreviouslySavedTokenAndKeepsRepository() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        store.save(GitHubBackupConfig("owner", "repository", "old-test-token"))

        store.save(GitHubBackupConfig("owner", "repository", ""))

        val loaded = GitHubBackupStore(preferences).load()
        assertEquals("owner", loaded.owner)
        assertEquals("repository", loaded.repository)
        assertEquals("", loaded.token)
        assertEquals("", preferences.getString("token", null))
        assertFalse(preferences.all.containsValue("old-test-token"))
    }

    @Test
    fun configToStringDoesNotExposeTokenBeforeOrAfterReload() {
        val preferences = MemoryPreferences()
        val config = GitHubBackupConfig("owner", "repository", "private-test-token")
        GitHubBackupStore(preferences).save(config)

        val loaded = GitHubBackupStore(preferences).load()

        assertEquals(config.token, loaded.token)
        assertFalse(config.toString().contains(config.token))
        assertFalse(loaded.toString().contains(config.token))
    }

    @Test
    fun saveWritesOnlyOwnerRepositoryAndTokenWithoutSettingsChannel() {
        val preferences = MemoryPreferences()

        GitHubBackupStore(preferences).save(GitHubBackupConfig("owner", "repository", "test-token"))

        assertEquals(
            mapOf("owner" to "owner", "repository" to "repository", "token" to "test-token"),
            preferences.all,
        )
    }

    @Test
    fun oldConfigurationDoesNotImplyConnection() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        assertFalse(store.isConnected())
        assertTrue(preferences.all.isEmpty())

        store.save(GitHubBackupConfig("owner", "repository", "test-token"))
        val saved = preferences.all

        assertFalse(GitHubBackupStore(preferences).isConnected())
        assertEquals(saved, preferences.all)
        assertFalse(preferences.contains("connected"))
    }

    @Test
    fun saveConnectedCommitsAllFieldsTogetherAndReloads() {
        val preferences = MemoryPreferences()
        val config = GitHubBackupConfig("owner", "repository", "test-token")

        GitHubBackupStore(preferences).saveConnected(config)

        val expected = mapOf(
            "owner" to config.owner,
            "repository" to config.repository,
            "token" to config.token,
            "connected" to true,
        )
        assertEquals(expected, preferences.all)
        assertEquals(listOf(expected), preferences.committedSnapshots)
        assertEquals(0, preferences.applyCount)
        val reloaded = GitHubBackupStore(preferences.reopened())
        assertEquals(config.owner, reloaded.load().owner)
        assertEquals(config.repository, reloaded.load().repository)
        assertEquals(config.token, reloaded.load().token)
        assertTrue(reloaded.isConnected())
    }

    @Test
    fun connectionRequiresBooleanTrueAndValidLoadedConfiguration() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val valid = GitHubBackupConfig("owner", "repository", "test-token")
        store.save(valid)
        preferences.edit().putBoolean("connected", false).apply()
        assertFalse(store.isConnected())
        preferences.edit().putString("connected", "true").apply()
        assertFalse(store.isConnected())
        preferences.edit().putBoolean("connected", false).apply()

        listOf(
            GitHubBackupConfig("", "repository", "test-token"),
            GitHubBackupConfig("owner", "..", "test-token"),
            GitHubBackupConfig("owner", "repository", ""),
            GitHubBackupConfig("owner", "repository", "token with spaces"),
        ).forEach { invalid ->
            store.save(invalid)
            preferences.edit().putBoolean("connected", true).apply()
            assertFalse(store.isConnected())
        }
    }

    @Test
    fun saveInvalidatesPreviouslyVerifiedConnectionEvenForSameConfig() {
        val preferences = MemoryPreferences()
        val config = GitHubBackupConfig("owner", "repository", "test-token")
        val store = GitHubBackupStore(preferences)
        store.saveConnected(config)

        store.save(config)

        assertFalse(GitHubBackupStore(preferences).isConnected())
        assertFalse(preferences.contains("connected"))
        assertEquals(setOf("connected"), preferences.removedKeys)
        assertEquals(setOf("owner", "repository", "token"), preferences.all.keys)
    }

    @Test
    fun saveDoesNotRemoveAnExplicitFalseConnectionFlag() {
        val preferences = MemoryPreferences()
        preferences.edit().putBoolean("connected", false).apply()

        GitHubBackupStore(preferences).save(GitHubBackupConfig("owner", "repository", "test-token"))

        assertEquals(false, preferences.all["connected"])
        assertTrue(preferences.removedKeys.isEmpty())
    }

    @Test
    fun failedCommitRestoresOldConnectionWithoutLosingStampOrPersistingFailedTokenLater() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val old = GitHubBackupConfig("old-owner", "old-repository", "old-token")
        val config = GitHubBackupConfig("new-owner", "new-repository", "private-test-token")
        store.saveConnected(old)
        store.saveLastBackup(old, backupItem())
        val persisted = preferences.all
        val stamp = store.loadLastBackup(old)
        preferences.commitSucceeds = false

        assertSaveConnectedFailsSafely(store, config)

        assertEquals(persisted, preferences.all)
        assertConnection(store, old, connected = true)
        assertConnection(GitHubBackupStore(preferences), old, connected = true)
        assertEquals(stamp, store.loadLastBackup(old))
        assertEquals(3, preferences.committedSnapshots.size)
        assertEquals(config.token, preferences.committedSnapshots[1]["token"])
        assertEquals(persisted, preferences.committedSnapshots.last())
        assertEquals(persisted, preferences.reopened().all)
        assertEquals(1, preferences.applyCount)

        store.saveLastBackup(config, backupItem(size = 456L))

        val reloaded = GitHubBackupStore(preferences.reopened())
        assertConnection(reloaded, old, connected = true)
        assertFalse(preferences.reopened().all.containsValue(config.token))
        assertEquals(456L, reloaded.loadLastBackup(config)?.size)
    }

    @Test
    fun failedCommitRestoresEmptyInitialStateAndCannotPersistFailedTokenLater() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val config = GitHubBackupConfig("owner", "repository", "private-test-token")
        val empty = GitHubBackupConfig("", "", "")
        preferences.commitSucceeds = false

        assertSaveConnectedFailsSafely(store, config)

        assertTrue(preferences.all.isEmpty())
        assertConnection(store, empty, connected = false)
        assertConnection(GitHubBackupStore(preferences), empty, connected = false)
        assertTrue(preferences.reopened().all.isEmpty())
        assertEquals(2, preferences.committedSnapshots.size)
        assertEquals(config.token, preferences.committedSnapshots.first()["token"])
        assertTrue(preferences.committedSnapshots.last().isEmpty())
        assertEquals(0, preferences.applyCount)

        store.saveLastBackup(config, backupItem())

        assertConnection(GitHubBackupStore(preferences.reopened()), empty, connected = false)
        assertFalse(preferences.reopened().all.containsValue(config.token))
    }

    @Test
    fun failedCommitRestoresKeyPresenceAndExplicitFalseWithoutRemovingStamp() {
        val initial = mapOf("owner" to "", "token" to "", "connected" to false)
        val preferences = MemoryPreferences(initial)
        val store = GitHubBackupStore(preferences)
        val config = GitHubBackupConfig("owner", "repository", "private-test-token")
        store.saveLastBackup(config, backupItem())
        val saved = preferences.all
        preferences.commitSucceeds = false

        assertSaveConnectedFailsSafely(store, config)

        assertEquals(saved, preferences.all)
        assertFalse(preferences.contains("repository"))
        assertFalse(store.isConnected())
        assertEquals(saved, preferences.reopened().all)
    }

    @Test
    fun commitAndRollbackExceptionsAfterMemoryUpdateRestoreStateAndThrowOnlySafeException() {
        listOf(false, true).forEach { initiallyConnected ->
            val preferences = MemoryPreferences()
            val store = GitHubBackupStore(preferences)
            val old = GitHubBackupConfig("old-owner", "old-repository", "old-token")
            val config = GitHubBackupConfig("owner", "repository", "private-test-token")
            if (initiallyConnected) store.saveConnected(old)
            store.saveLastBackup(old, backupItem())
            val saved = preferences.all
            val previousAttempts = preferences.committedSnapshots.size
            val expected = if (initiallyConnected) old else GitHubBackupConfig("", "", "")
            preferences.commitException = IllegalStateException("存储失败 ${config.token}")

            assertSaveConnectedFailsSafely(store, config)

            assertEquals(saved, preferences.all)
            assertConnection(store, expected, initiallyConnected)
            assertConnection(GitHubBackupStore(preferences), expected, initiallyConnected)
            assertEquals(saved, preferences.reopened().all)
            assertEquals(previousAttempts + 2, preferences.committedSnapshots.size)
            assertEquals(config.token, preferences.committedSnapshots[previousAttempts]["token"])
            assertEquals(saved, preferences.committedSnapshots.last())

            store.saveLastBackup(config, backupItem(size = 456L))

            assertConnection(GitHubBackupStore(preferences.reopened()), expected, initiallyConnected)
            assertFalse(preferences.reopened().all.containsValue(config.token))
            assertEquals(456L, store.loadLastBackup(config)?.size)
        }
    }

    private fun assertSaveConnectedFailsSafely(store: GitHubBackupStore, config: GitHubBackupConfig) {
        try {
            store.saveConnected(config)
            fail("提交失败或抛异常时不应正常返回")
        } catch (error: GitHubBackupException) {
            assertEquals("GitHub 连接配置保存失败，请重试", error.message)
            assertFalse(error.toString().contains(config.token))
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
        }
    }

    private fun assertConnection(store: GitHubBackupStore, expected: GitHubBackupConfig, connected: Boolean) {
        val loaded = store.load()
        assertEquals(expected.owner, loaded.owner)
        assertEquals(expected.repository, loaded.repository)
        assertEquals(expected.token, loaded.token)
        assertEquals(connected, store.isConnected())
    }

    @Test
    fun saveConnectedRejectsInvalidConfigBeforeWriting() {
        val preferences = MemoryPreferences()

        try {
            GitHubBackupStore(preferences).saveConnected(GitHubBackupConfig("owner", "repository", ""))
            fail("无效配置不应被标记为已连接")
        } catch (_: GitHubBackupException) {
            assertTrue(preferences.all.isEmpty())
            assertTrue(preferences.committedSnapshots.isEmpty())
        }
    }

    @Test
    fun lastBackupDefaultsToNullWithoutWriting() {
        val preferences = MemoryPreferences()

        assertNull(GitHubBackupStore(preferences).loadLastBackup(GitHubBackupConfig("owner", "repository", "")))
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun lastBackupReloadsOnlyStampFieldsWithoutTokenCopy() {
        val preferences = MemoryPreferences()
        val config = GitHubBackupConfig("Owner", "Repository", "private-test-token")
        val item = backupItem(size = 0L)

        GitHubBackupStore(preferences).saveLastBackup(config, item)

        assertEquals(
            mapOf(
                "last_backup_owner" to config.owner,
                "last_backup_repository" to config.repository,
                "last_backup_created_at" to item.createdAt,
                "last_backup_size" to item.size,
            ),
            preferences.all,
        )
        assertFalse(preferences.all.containsValue(config.token))
        assertEquals(
            GitHubBackupStamp(config.owner, config.repository, item.createdAt, 0L),
            GitHubBackupStore(preferences.reopened()).loadLastBackup(config),
        )
    }

    @Test
    fun lastBackupMatchesOwnerAndRepositoryIgnoringCaseButNotOtherAccountsOrRepositories() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val config = GitHubBackupConfig("Owner", "Repository", "first-token")
        val item = backupItem()
        store.saveLastBackup(config, item)
        val expected = GitHubBackupStamp(config.owner, config.repository, item.createdAt, item.size)

        assertEquals(expected, store.loadLastBackup(GitHubBackupConfig("OWNER", "repository", "other-token")))
        listOf(
            GitHubBackupConfig("other-owner", "Repository", "other-token"),
            GitHubBackupConfig("Owner", "other-repository", "first-token"),
        ).forEach { other ->
            store.saveConnected(other)
            assertNull(GitHubBackupStore(preferences).loadLastBackup(other))
            assertEquals(expected, store.loadLastBackup(config))
        }
    }

    @Test
    fun lastBackupRejectsInvalidTimeAndNegativeSize() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val config = GitHubBackupConfig("owner", "repository", "test-token")
        listOf("", "invalid", "2026-11-22", "2026-13-22T12:00:00Z").forEach { time ->
            store.saveLastBackup(config, backupItem(createdAt = time))
            assertNull(store.loadLastBackup(config))
        }
        store.saveLastBackup(config, backupItem(size = -1L))
        assertNull(store.loadLastBackup(config))
    }

    @Test
    fun lastBackupRejectsIncompleteOrWronglyTypedRecords() {
        val config = GitHubBackupConfig("owner", "repository", "test-token")
        listOf("last_backup_owner", "last_backup_repository", "last_backup_created_at", "last_backup_size")
            .forEach { missing ->
                val preferences = MemoryPreferences()
                val store = GitHubBackupStore(preferences)
                store.saveLastBackup(config, backupItem())
                preferences.edit().remove(missing).apply()
                assertNull(store.loadLastBackup(config))
            }
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        store.saveLastBackup(config, backupItem())
        preferences.edit().putString("last_backup_size", "123").apply()
        assertNull(store.loadLastBackup(config))
    }

    @Test
    fun clearingTokenInvalidatesConnectionWithoutLosingOrDuplicatingStamp() {
        val preferences = MemoryPreferences()
        val store = GitHubBackupStore(preferences)
        val config = GitHubBackupConfig("owner", "repository", "private-test-token")
        val item = backupItem()
        store.saveConnected(config)
        store.saveLastBackup(config, item)
        val cleared = GitHubBackupConfig(config.owner, config.repository, "")

        store.save(cleared)

        val reloaded = GitHubBackupStore(preferences.reopened())
        assertFalse(reloaded.isConnected())
        assertEquals("", reloaded.load().token)
        assertFalse(preferences.all.containsValue(config.token))
        assertEquals(
            GitHubBackupStamp(config.owner, config.repository, item.createdAt, item.size),
            reloaded.loadLastBackup(cleared),
        )
    }

    private fun backupItem(
        createdAt: String = "2026-11-22T12:34:56Z",
        size: Long = 123L,
    ) = GitHubBackupItem(
        assetId = 1L,
        name = "backup.zip",
        size = size,
        createdAt = createdAt,
        releaseName = "backup",
        tag = "backup-tag",
    )

    private class MemoryPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
        private val values = initial.toMutableMap()
        private val persisted = initial.toMutableMap()
        var commitSucceeds = true
        var commitException: RuntimeException? = null
        var applyCount = 0
            private set
        val committedSnapshots = mutableListOf<Map<String, Any?>>()
        val removedKeys = mutableSetOf<String>()

        fun reopened() = MemoryPreferences(persisted)

        override fun contains(key: String?) = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as Set<*>?)?.map { it as String }?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
                pending[requireNotNull(key)] = value
                removed -= key
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                removed += requireNotNull(key)
                pending -= key
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun apply() {
                applyCount++
                updateMemory()
                persisted.clear()
                persisted.putAll(values)
            }

            override fun commit(): Boolean {
                // 模拟 SharedPreferences 先更新内存，再在落盘阶段失败或抛异常。
                updateMemory()
                committedSnapshots += values.toMap()
                commitException?.let { throw it }
                if (commitSucceeds) {
                    persisted.clear()
                    persisted.putAll(values)
                }
                return commitSucceeds
            }

            private fun updateMemory() {
                val updated = if (clearAll) mutableMapOf<String, Any?>() else values.toMutableMap()
                removedKeys += removed
                removed.forEach(updated::remove)
                pending.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
                values.clear()
                values.putAll(updated)
                pending.clear()
                removed.clear()
                clearAll = false
            }
        }
    }
}
