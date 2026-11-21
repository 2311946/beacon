package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerDisplaySize
import me.rerere.rikkahub.data.datastore.StickerGroup
import me.rerere.rikkahub.data.datastore.UsageBudgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.format.DateTimeParseException
import java.util.UUID

class BackupFormatTest {
    @get:Rule val temporary = TemporaryFolder()

    private val json = Json { encodeDefaults = true }
    private val stores = mutableMapOf<String, MemoryPreferences>()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir() = File(temporary.root, "files")

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals(Context.MODE_PRIVATE, mode)
            return preferences(name)
        }
    }

    private fun preferences(name: String) = stores.getOrPut(name) { MemoryPreferences() }
    private fun general() = preferences("rikkahub.preferences")
    private fun budget() = preferences("usage_budget")
    private fun stickers() = preferences("url_stickers")
    private fun id() = UUID.randomUUID().toString()
    private fun stickerDirectory() = File(context.filesDir, "stickers")

    private fun budgetJson(
        month: String = "2026-11",
        amount: String = "200.50",
        monthlyCosts: Map<String, String> = mapOf("2026-10" to "12.34", "2026-11" to "0"),
    ) = json.encodeToString(UsageBudgetState(month, amount, monthlyCosts))

    private fun incomingPreferences() = BackupPreferences(
        usageBudgetState = budgetJson(amount = "999"),
        colorMode = "DARK",
        amoledDark = true,
        createNewConversationOnStart = true,
        modelListCollapsedProviders = "[\"new-provider\"]",
        searchPageSortOrder = "new-order",
    )

    private fun remoteGroup() = StickerGroup(
        id = id(),
        name = "远程表情",
        stickers = listOf(
            Sticker(id(), "https://example.com/First.PNG?token=AbC", "开心"),
            Sticker(id(), "http://example.com/second.gif", ""),
        ),
    )

    private fun stagePreferences(value: BackupPreferences = incomingPreferences()): File =
        temporary.newFolder("pending").also {
            File(it, "preferences.json").writeText(json.encodeToString(value))
        }

    @Test
    fun captureIncludesOnlyWhitelistedPreferencesAndExcludesLastConversationId() {
        val state = budgetJson()
        budget().edit().putString("state", state).putString("unrelated", "budget-secret").commit()
        general().edit()
            .putString("colorMode", "LIGHT")
            .putBoolean("amoledDark", false)
            .putBoolean("create_new_conversation_on_start", true)
            .putString("model_list_collapsed_providers", "[\"provider\"]")
            .putString("search_page_sort_order", "old-order")
            .putString("lastConversationId", "private-conversation")
            .putString("unrelated", "general-secret")
            .commit()
        stickers().edit().putString("groups", "[]").commit()
        val before = stores.mapValues { it.value.snapshot() }

        val captured = BackupFormat.capturePreferences(context)

        assertEquals(
            BackupPreferences(
                usageBudgetState = state,
                colorMode = "LIGHT",
                amoledDark = false,
                createNewConversationOnStart = true,
                modelListCollapsedProviders = "[\"provider\"]",
                searchPageSortOrder = "old-order",
            ),
            captured,
        )
        val exported = json.encodeToString(captured)
        val keys = json.parseToJsonElement(exported).jsonObject
        assertEquals(
            setOf("schemaVersion", "usageBudgetState", "colorMode", "amoledDark",
                "createNewConversationOnStart", "modelListCollapsedProviders", "searchPageSortOrder"),
            keys.keys,
        )
        listOf("lastConversationId", "private-conversation", "budget-secret", "general-secret", "groups")
            .forEach { assertFalse(exported.contains(it)) }
        assertEquals(before, stores.mapValues { it.value.snapshot() })
    }

    @Test
    fun captureLeavesMissingWhitelistedKeysNull() {
        general().edit().putString("lastConversationId", "keep").commit()

        assertEquals(BackupPreferences(), BackupFormat.capturePreferences(context))
        assertEquals(mapOf("lastConversationId" to "keep"), general().snapshot())
        assertTrue(budget().snapshot().isEmpty())
    }

    @Test
    fun validatePreferencesAcceptsSupportedSchemaWithOptionalOrValidBudget() {
        BackupFormat.validatePreferences(BackupPreferences(), json)
        BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = budgetJson()), json)
        BackupFormat.validatePreferences(
            BackupPreferences(usageBudgetState = budgetJson(amount = "0", monthlyCosts = emptyMap())), json,
        )
    }

    @Test
    fun validatePreferencesRejectsUnsupportedSchemas() {
        listOf(0, 2, -1).forEach { version ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BackupFormat.validatePreferences(BackupPreferences(schemaVersion = version), json)
            }
            assertEquals("Unsupported backup preferences schema: $version", failure.message)
        }
    }

    @Test
    fun validatePreferencesRejectsUnparseableBudgetJson() {
        assertThrows(SerializationException::class.java) {
            BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = "not-json"), json)
        }
    }

    @Test
    fun validatePreferencesRejectsUnparseableOrNegativeBudgetAmounts() {
        listOf("not-money", "NaN", "-0.01").forEach { amount ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = budgetJson(amount = amount)), json)
            }
            assertEquals("Invalid usage budget", failure.message)
        }
    }

    @Test
    fun validatePreferencesRejectsUnparseableOrNegativeMonthlyCosts() {
        listOf("not-money", "Infinity", "-1").forEach { amount ->
            val state = budgetJson(monthlyCosts = mapOf("2026-10" to amount))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = state), json)
            }
            assertEquals("Invalid usage budget amount", failure.message)
        }
    }

    @Test
    fun validatePreferencesRejectsUnparseableCurrentMonth() {
        listOf("not-a-month", "2026-13", "2026-11-01").forEach { month ->
            assertThrows(DateTimeParseException::class.java) {
                BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = budgetJson(month = month)), json)
            }
        }
    }

    @Test
    fun validatePreferencesRejectsUnparseableHistoricalMonth() {
        listOf("not-a-month", "2026-00", "2026-10-01").forEach { month ->
            val state = budgetJson(monthlyCosts = mapOf(month to "1.25"))
            assertThrows(DateTimeParseException::class.java) {
                BackupFormat.validatePreferences(BackupPreferences(usageBudgetState = state), json)
            }
        }
    }

    @Test
    fun rollbackRemovesOriginallyMissingKeysAndPreservesLastConversationId() {
        general().edit().putString("lastConversationId", "keep").putString("unrelated", "keep-too").commit()
        budget().edit().putString("unrelated", "budget-extra").commit()
        val originalGeneral = general().snapshot()
        val originalBudget = budget().snapshot()
        val incoming = incomingPreferences()
        val pending = stagePreferences(incoming)

        BackupFormat.preparePreferenceRollback(context, pending, json)
        BackupFormat.restorePreferences(context, incoming, json)
        assertEquals(incoming, BackupFormat.capturePreferences(context))
        assertEquals("keep", general().getString("lastConversationId", null))

        BackupFormat.rollbackPreferences(context, pending, json)

        assertEquals(originalGeneral, general().snapshot())
        assertEquals(originalBudget, budget().snapshot())
        assertFalse(budget().contains("state"))
        listOf("colorMode", "amoledDark", "create_new_conversation_on_start",
            "model_list_collapsed_providers", "search_page_sort_order").forEach {
            assertFalse(general().contains(it))
        }
        assertEquals("keep", general().getString("lastConversationId", null))
    }

    @Test
    fun rollbackRestoresExistingValuesAndRemovesOnlyOriginallyMissingKeys() {
        budget().edit().putString("state", budgetJson()).commit()
        general().edit()
            .putString("colorMode", "LIGHT")
            .putBoolean("amoledDark", false)
            .putString("lastConversationId", "keep")
            .commit()
        val originalGeneral = general().snapshot()
        val originalBudget = budget().snapshot()
        val pending = stagePreferences()

        BackupFormat.preparePreferenceRollback(context, pending, json)
        BackupFormat.restorePreferences(context, incomingPreferences(), json)
        assertEquals(incomingPreferences(), BackupFormat.capturePreferences(context))
        BackupFormat.rollbackPreferences(context, pending, json)

        assertEquals(originalGeneral, general().snapshot())
        assertEquals(originalBudget, budget().snapshot())
    }

    @Test
    fun repeatedPrepareDoesNotOverwriteOriginalPreferenceOrStickerSnapshot() {
        budget().edit().putString("state", budgetJson()).commit()
        general().edit().putString("colorMode", "LIGHT").putString("lastConversationId", "keep").commit()
        val originalGroups = json.encodeToString(listOf(remoteGroup()))
        stickers().edit().putString("groups", originalGroups).putString("display_size", "SMALL").commit()
        val originals = stores.mapValues { it.value.snapshot() }
        val pending = stagePreferences()
        val stickerText = json.encodeToString(BackupStickerSnapshot(displaySize = "LARGE", groups = listOf(remoteGroup())))
        File(pending, "stickers.json").writeText(stickerText)

        BackupFormat.preparePreferenceRollback(context, pending, json)
        val snapshotFile = File(pending, "preference-originals.json")
        val originalSnapshot = snapshotFile.readText()
        BackupFormat.restorePreferences(context, incomingPreferences(), json)
        BackupFormat.restoreStickers(context, stickerDirectory(), json, stickerText)
        assertEquals(incomingPreferences(), BackupFormat.capturePreferences(context))
        assertEquals("LARGE", stickers().getString("display_size", null))

        BackupFormat.preparePreferenceRollback(context, pending, json)

        assertEquals(originalSnapshot, snapshotFile.readText())
        BackupFormat.rollbackPreferences(context, pending, json)
        assertEquals(originals, stores.mapValues { it.value.snapshot() })
    }

    @Test
    fun pendingRestoreSettingsFailureRollsBackActualBudgetGeneralAndStickerPreferences() {
        budget().edit().putString("state", budgetJson()).putString("unrelated", "budget-extra").commit()
        general().edit()
            .putString("colorMode", "LIGHT")
            .putBoolean("amoledDark", false)
            .putBoolean("create_new_conversation_on_start", false)
            .putString("model_list_collapsed_providers", "[]")
            .putString("search_page_sort_order", "old-order")
            .putString("lastConversationId", "keep")
            .putString("unrelated", "general-extra")
            .commit()
        stickers().edit()
            .putString("groups", json.encodeToString(listOf(remoteGroup())))
            .putString("display_size", "SMALL")
            .putString("unrelated", "sticker-extra")
            .commit()
        val originals = stores.mapValues { it.value.snapshot() }
        val root = File(temporary.root, "restore")
        val restore = PendingRestore(root, File(temporary.root, "databases/rikka_hub"), context.filesDir)
        val staging = restore.createStagingDirectory()
        val incoming = incomingPreferences()
        val incomingStickers = BackupStickerSnapshot(displaySize = "LARGE", groups = listOf(remoteGroup()))
        File(staging, "preferences.json").writeText(json.encodeToString(incoming))
        File(staging, "stickers.json").writeText(json.encodeToString(incomingStickers))
        File(staging, "settings.json").writeText("new settings")
        restore.publish(staging)
        val callbacks = mutableListOf<String>()
        val failure = IOException("模拟设置写入失败")

        val thrown = assertThrows(RestoreFailedException::class.java) {
            runBlocking {
                restore.apply(
                    preparePreferences = {
                        BackupFormat.preparePreferenceRollback(context, it, json)
                        callbacks += "prepare"
                    },
                    restorePreferences = {
                        BackupFormat.restorePreferences(context, json.decodeFromString<BackupPreferences>(it), json)
                        callbacks += "preferences"
                    },
                    restoreStickers = {
                        BackupFormat.restoreStickers(context, stickerDirectory(), json, it)
                        callbacks += "stickers"
                    },
                    rollbackPreferences = {
                        BackupFormat.rollbackPreferences(context, it, json)
                        callbacks += "rollback"
                    },
                    restoreSettings = {
                        assertEquals("new settings", it)
                        assertEquals(incoming, BackupFormat.capturePreferences(context))
                        assertEquals(json.encodeToString(incomingStickers.groups), stickers().getString("groups", null))
                        assertEquals("LARGE", stickers().getString("display_size", null))
                        assertEquals("keep", general().getString("lastConversationId", null))
                        callbacks += "settings"
                        throw failure
                    },
                )
            }
        }

        assertSame(failure, thrown.cause)
        assertEquals(listOf("prepare", "preferences", "stickers", "settings", "rollback"), callbacks)
        assertEquals(originals, stores.mapValues { it.value.snapshot() })
        assertFalse(File(root, "pending").exists())
        val failed = root.listFiles()!!.single { it.name.startsWith("failed-") }
        assertTrue(File(failed, "preference-originals.json").isFile)
        assertFalse(stickerDirectory().exists())
    }

    @Test
    fun remoteOnlyStickersRestoreWithoutSourceOrDestinationDirectory() {
        val source = File(temporary.root, "missing-source/stickers")
        val groups = listOf(remoteGroup())
        val snapshot = BackupStickerSnapshot(displaySize = StickerDisplaySize.LARGE.name, groups = groups)
        assertFalse(source.exists())
        assertFalse(stickerDirectory().exists())

        BackupFormat.restoreStickers(context, source, json, json.encodeToString(snapshot))

        assertEquals(groups, json.decodeFromString<List<StickerGroup>>(stickers().getString("groups", null)!!))
        assertEquals("LARGE", stickers().getString("display_size", null))
        assertFalse(source.exists())
        assertFalse(stickerDirectory().exists())
    }

    @Test
    fun emptyStickersRestoreWithoutLocalDirectoryAndKeepUnspecifiedDisplaySize() {
        val source = File(temporary.root, "missing-source/stickers")
        stickers().edit().putString("groups", json.encodeToString(listOf(remoteGroup())))
            .putString("display_size", "SMALL").commit()

        BackupFormat.restoreStickers(context, source, json, json.encodeToString(BackupStickerSnapshot()))

        assertEquals(emptyList<StickerGroup>(), json.decodeFromString<List<StickerGroup>>(stickers().getString("groups", null)!!))
        assertEquals("SMALL", stickers().getString("display_size", null))
        assertFalse(source.exists())
        assertFalse(stickerDirectory().exists())
    }

    @Test
    fun validateStickersAcceptsExistingRelativeLocalFileWithoutAndroidUri() {
        val directory = temporary.newFolder("stickers")
        val file = File(directory, "${id()}.png").apply { writeText("image") }
        val group = StickerGroup(id(), "本地表情", listOf(Sticker(id(), file.name)))

        BackupFormat.validateStickersJson(
            directory, json, json.encodeToString(BackupStickerSnapshot(groups = listOf(group))),
        )
    }

    @Test
    fun validateStickersRejectsRelativePathTraversalEvenWhenFileExists() {
        val directory = temporary.newFolder("stickers")
        val outside = File(temporary.root, "${id()}.png").apply { writeText("image") }
        val group = StickerGroup(id(), "越界表情", listOf(Sticker(id(), "../${outside.name}")))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                directory, json, json.encodeToString(BackupStickerSnapshot(groups = listOf(group))),
            )
        }

        assertEquals("Invalid local sticker path", failure.message)
        assertTrue(outside.isFile)
    }

    @Test
    fun validateStickersRejectsMissingLocalFile() {
        val directory = temporary.newFolder("stickers")
        val missing = "${id()}.webp"
        val group = StickerGroup(id(), "缺失表情", listOf(Sticker(id(), missing)))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                directory, json, json.encodeToString(BackupStickerSnapshot(groups = listOf(group))),
            )
        }

        assertEquals("Backup is missing local sticker file: $missing", failure.message)
        assertFalse(File(directory, missing).exists())
    }

    @Test
    fun validateStickersRejectsInvalidDisplaySize() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                stickerDirectory(), json, json.encodeToString(BackupStickerSnapshot(displaySize = "HUGE")),
            )
        }

        assertEquals("Invalid sticker display size", failure.message)
    }

    @Test
    fun validateStickersRejectsDuplicateGroupUuids() {
        val group = remoteGroup()
        val duplicate = group.copy(name = "重复分组", stickers = emptyList())
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                stickerDirectory(), json, json.encodeToString(BackupStickerSnapshot(groups = listOf(group, duplicate))),
            )
        }

        assertEquals("Duplicate sticker group id", failure.message)
    }

    @Test
    fun validateStickersRejectsDuplicateStickerUuidsAcrossGroups() {
        val first = remoteGroup()
        val second = remoteGroup().copy(stickers = listOf(first.stickers.first().copy(name = "重复表情")))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                stickerDirectory(), json, json.encodeToString(BackupStickerSnapshot(groups = listOf(first, second))),
            )
        }

        assertEquals("Duplicate sticker id", failure.message)
    }

    @Test
    fun validateStickersRejectsUuidSharedByGroupAndSticker() {
        val group = remoteGroup().let { it.copy(stickers = listOf(it.stickers.first().copy(id = it.id))) }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BackupFormat.validateStickersJson(
                stickerDirectory(), json, json.encodeToString(BackupStickerSnapshot(groups = listOf(group))),
            )
        }

        assertEquals("Duplicate sticker id", failure.message)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        fun snapshot(): Map<String, Any?> = values.toMap()

        override fun contains(key: String?) = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
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
            override fun putStringSet(key: String?, value: MutableSet<String>?) = put(key, value?.toSet())
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                removed += requireNotNull(key)
                pending -= key
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removed.forEach(values::remove)
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
        }
    }
}
