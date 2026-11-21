package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.StickerDisplaySize
import me.rerere.rikkahub.data.datastore.StickerGroup
import me.rerere.rikkahub.data.datastore.UsageBudgetState
import java.io.File
import java.net.URI
import java.util.UUID

@Serializable
internal data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val components: BackupManifestComponents = BackupManifestComponents(),
    val databaseUserVersion: Int? = null,
) {
    companion object {
        const val FORMAT_VERSION = 2
    }
}

@Serializable
internal data class BackupManifestComponents(
    val settings: Boolean = true,
    val database: Boolean = false,
    val preferences: Boolean = true,
    val stickers: Boolean = false,
    val files: Boolean = false,
)

@Serializable
internal data class BackupPreferences(
    val schemaVersion: Int = 1,
    val usageBudgetState: String? = null,
    val colorMode: String? = null,
    val amoledDark: Boolean? = null,
    val createNewConversationOnStart: Boolean? = null,
    val modelListCollapsedProviders: String? = null,
    val searchPageSortOrder: String? = null,
)

@Serializable
internal data class BackupStickerSnapshot(
    val schemaVersion: Int = 1,
    val displaySize: String? = null,
    val groups: List<StickerGroup> = emptyList(),
)

@Serializable
internal data class BackupPreferenceOriginals(
    val preferences: BackupPreferences? = null,
    val restoreStickers: Boolean = false,
    val stickerGroups: String? = null,
    val stickerDisplaySize: String? = null,
)

internal object BackupFormat {
    private const val URL_STICKERS_PREFERENCES = "url_stickers"
    private const val GROUPS_KEY = "groups"
    private const val DISPLAY_SIZE_KEY = "display_size"
    private const val USAGE_BUDGET_PREFERENCES = "usage_budget"
    private const val USAGE_BUDGET_KEY = "state"
    private const val GENERAL_PREFERENCES = "rikkahub.preferences"
    private const val STICKERS_DIRECTORY = "stickers"

    fun hasStickerData(context: Context): Boolean {
        val preferences = context.getSharedPreferences(URL_STICKERS_PREFERENCES, Context.MODE_PRIVATE)
        return preferences.contains(GROUPS_KEY) || preferences.contains(DISPLAY_SIZE_KEY)
    }

    fun capturePreferences(context: Context): BackupPreferences {
        val general = context.getSharedPreferences(GENERAL_PREFERENCES, Context.MODE_PRIVATE)
        val budget = context.getSharedPreferences(USAGE_BUDGET_PREFERENCES, Context.MODE_PRIVATE)
        return BackupPreferences(
            usageBudgetState = budget.getString(USAGE_BUDGET_KEY, null),
            colorMode = general.getString("colorMode", null),
            amoledDark = general.getBooleanOrNull("amoledDark"),
            createNewConversationOnStart = general.getBooleanOrNull("create_new_conversation_on_start"),
            modelListCollapsedProviders = general.getString("model_list_collapsed_providers", null),
            searchPageSortOrder = general.getString("search_page_sort_order", null),
        )
    }

    fun validatePreferences(value: BackupPreferences, json: Json) {
        require(value.schemaVersion == 1) { "Unsupported backup preferences schema: ${value.schemaVersion}" }
        value.usageBudgetState?.let { validateUsageBudget(it, json) }
    }

    fun restorePreferences(context: Context, value: BackupPreferences, json: Json) {
        validatePreferences(value, json)
        writePreferences(context, value, removeMissing = false)
    }

    private fun writePreferences(context: Context, value: BackupPreferences, removeMissing: Boolean) {
        val budget = context.getSharedPreferences(USAGE_BUDGET_PREFERENCES, Context.MODE_PRIVATE)
        val general = context.getSharedPreferences(GENERAL_PREFERENCES, Context.MODE_PRIVATE)
        val budgetEditor = budget.edit()
        if (removeMissing) budgetEditor.remove(USAGE_BUDGET_KEY)
        value.usageBudgetState?.let { budgetEditor.putString(USAGE_BUDGET_KEY, it) }
        check(budgetEditor.commit()) { "Cannot restore usage budget preferences" }

        val generalEditor = general.edit()
        if (removeMissing) {
            listOf("colorMode", "amoledDark", "create_new_conversation_on_start",
                "model_list_collapsed_providers", "search_page_sort_order").forEach(generalEditor::remove)
        }
        value.colorMode?.let { generalEditor.putString("colorMode", it) }
        value.amoledDark?.let { generalEditor.putBoolean("amoledDark", it) }
        value.createNewConversationOnStart?.let {
            generalEditor.putBoolean("create_new_conversation_on_start", it)
        }
        value.modelListCollapsedProviders?.let {
            generalEditor.putString("model_list_collapsed_providers", it)
        }
        value.searchPageSortOrder?.let {
            generalEditor.putString("search_page_sort_order", it)
        }
        check(generalEditor.commit()) { "Cannot restore general preferences" }
    }

    fun preparePreferenceRollback(context: Context, pending: File, json: Json) {
        val preferences = File(pending, "preferences.json").isFile
        val stickers = File(pending, "stickers.json").isFile
        if (!preferences && !stickers) return
        val original = File(pending, "preference-originals.json")
        if (original.exists()) {
            // Never replace the original snapshot with partially restored values after process loss.
            json.decodeFromString<BackupPreferenceOriginals>(original.readText())
            return
        }
        val store = context.getSharedPreferences(URL_STICKERS_PREFERENCES, Context.MODE_PRIVATE)
        PendingRestore.writeDurably(original, json.encodeToString(BackupPreferenceOriginals(
            preferences = if (preferences) capturePreferences(context) else null,
            restoreStickers = stickers,
            stickerGroups = if (stickers) store.getString(GROUPS_KEY, null) else null,
            stickerDisplaySize = if (stickers) store.getString(DISPLAY_SIZE_KEY, null) else null,
        )))
    }

    fun rollbackPreferences(context: Context, pending: File, json: Json) {
        val original = File(pending, "preference-originals.json")
        if (!original.isFile) return // Preparation failed before any preference write.
        val snapshot = json.decodeFromString<BackupPreferenceOriginals>(original.readText())
        snapshot.preferences?.let { writePreferences(context, it, removeMissing = true) }
        if (snapshot.restoreStickers) {
            val editor = context.getSharedPreferences(URL_STICKERS_PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(GROUPS_KEY).remove(DISPLAY_SIZE_KEY)
            snapshot.stickerGroups?.let { editor.putString(GROUPS_KEY, it) }
            snapshot.stickerDisplaySize?.let { editor.putString(DISPLAY_SIZE_KEY, it) }
            check(editor.commit()) { "Cannot roll back sticker preferences" }
        }
    }

    fun captureStickers(context: Context, json: Json): String {
        val preferences = context.getSharedPreferences(URL_STICKERS_PREFERENCES, Context.MODE_PRIVATE)
        val groups = preferences.getString(GROUPS_KEY, null)
            ?.let { json.decodeFromString<List<StickerGroup>>(it) }
            ?: emptyList()
        validateStickerGroups(groups, File(context.filesDir, STICKERS_DIRECTORY), allowRelativeLocalPath = false)
        val localDirectory = File(context.filesDir, STICKERS_DIRECTORY)
        val exportedGroups = groups.map { group ->
            group.copy(stickers = group.stickers.map { sticker ->
                if (sticker.url.startsWith("file://")) {
                    val file = localFile(sticker.url, localDirectory)
                    require(file.isFile) { "Local sticker file is missing: ${file.name}" }
                    sticker.copy(url = file.name)
                } else {
                    sticker
                }
            })
        }
        val displaySize = preferences.getString(DISPLAY_SIZE_KEY, null)
        require(displaySize == null || StickerDisplaySize.entries.any { it.name == displaySize }) {
            "Invalid sticker display size"
        }
        return json.encodeToString(
            BackupStickerSnapshot(
                displaySize = displaySize,
                groups = exportedGroups,
            )
        )
    }

    fun validateStickersJson(stickerDirectory: File, json: Json, text: String) {
        val snapshot = json.decodeFromString<BackupStickerSnapshot>(text)
        require(snapshot.schemaVersion == 1) {
            "Unsupported backup stickers schema: ${snapshot.schemaVersion}"
        }
        validateStickerGroups(snapshot.groups, stickerDirectory, allowRelativeLocalPath = true)
        snapshot.groups.flatMap { it.stickers }.filter {
            !it.url.startsWith("http://") && !it.url.startsWith("https://")
        }.forEach {
            val file = relativeStickerFile(stickerDirectory, it.url)
            require(file.isFile) { "Backup is missing local sticker file: ${file.name}" }
        }
        snapshot.displaySize?.let { value ->
            require(StickerDisplaySize.entries.any { it.name == value }) { "Invalid sticker display size" }
        }
    }

    fun restoreStickers(context: Context, stickerDirectory: File, json: Json, text: String) {
        validateStickersJson(stickerDirectory, json, text)
        val snapshot = json.decodeFromString<BackupStickerSnapshot>(text)
        val localDirectory = File(context.filesDir, STICKERS_DIRECTORY)
        val restoredGroups = snapshot.groups.map { group ->
            group.copy(stickers = group.stickers.map { sticker ->
                if (sticker.url.startsWith("http://") || sticker.url.startsWith("https://")) {
                    sticker
                } else {
                    sticker.copy(url = Uri.fromFile(relativeStickerFile(localDirectory, sticker.url)).toString())
                }
            })
        }
        val preferences = context.getSharedPreferences(URL_STICKERS_PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()
            .putString(GROUPS_KEY, json.encodeToString(restoredGroups))
        snapshot.displaySize?.let { editor.putString(DISPLAY_SIZE_KEY, it) }
        check(editor.commit()) { "Cannot restore sticker preferences" }
    }

    private fun validateUsageBudget(text: String, json: Json) {
        val state = json.decodeFromString<UsageBudgetState>(text)
        java.time.YearMonth.parse(state.month)
        require((state.budget.toBigDecimalOrNull()?.signum() ?: -1) >= 0) { "Invalid usage budget" }
        state.monthlyCosts.forEach { (month, amount) ->
            java.time.YearMonth.parse(month)
            require((amount.toBigDecimalOrNull()?.signum() ?: -1) >= 0) { "Invalid usage budget amount" }
        }
    }

    private fun validateStickerGroups(
        groups: List<StickerGroup>,
        stickerDirectory: File,
        allowRelativeLocalPath: Boolean,
    ) {
        val ids = mutableSetOf<String>()
        groups.forEach { group ->
            requireValidId(group.id)
            require(ids.add(group.id)) { "Duplicate sticker group id" }
            require(group.name.isNotBlank()) { "Sticker group name is blank" }
            group.stickers.forEach { sticker ->
                requireValidId(sticker.id)
                require(ids.add(sticker.id)) { "Duplicate sticker id" }
                when {
                    sticker.url.startsWith("http://") || sticker.url.startsWith("https://") -> {
                        me.rerere.rikkahub.data.datastore.StickerStore.validateUrl(sticker.url)
                    }
                    allowRelativeLocalPath -> relativeStickerFile(stickerDirectory, sticker.url)
                    else -> localFile(sticker.url, stickerDirectory)
                }
            }
        }
    }

    private fun relativeStickerFile(stickerDirectory: File, relative: String): File {
        require(relative.isNotBlank() && relative == File(relative).name) {
            "Invalid local sticker path"
        }
        val file = File(stickerDirectory, relative).canonicalFile
        val root = stickerDirectory.canonicalFile
        require(file.parentFile == root) { "Local sticker is outside sticker directory" }
        require(file.extension.lowercase() in setOf("jpg", "png", "gif", "webp")) {
            "Invalid local sticker format"
        }
        requireValidId(file.nameWithoutExtension)
        return file
    }

    private fun localFile(url: String, directory: File): File {
        val uri = URI(url)
        require(uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
            "Invalid local sticker URL"
        }
        val file = File(uri).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "Local sticker is outside sticker directory" }
        require(file.extension.lowercase() in setOf("jpg", "png", "gif", "webp")) {
            "Invalid local sticker format"
        }
        requireValidId(file.nameWithoutExtension)
        return file
    }

    private fun requireValidId(id: String) {
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true)) {
            "Invalid backup sticker id"
        }
    }

    private fun android.content.SharedPreferences.getBooleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null
}
