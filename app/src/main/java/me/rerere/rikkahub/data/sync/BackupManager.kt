package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("$BACKUP_FILE_PREFIX${timestamp}-", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.awaitLoaded()
            val stickersJson = if (includeFiles && BackupFormat.hasStickerData(context)) {
                BackupFormat.captureStickers(context, json)
            } else null
            val preferences = BackupFormat.capturePreferences(context)
            BackupFormat.validatePreferences(preferences, json)
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val manifest = BackupManifest(
                    components = BackupManifestComponents(
                        database = includeDatabase,
                        stickers = stickersJson != null,
                        files = includeFiles,
                    ),
                    databaseUserVersion = if (includeDatabase) {
                        database.openHelper.readableDatabase.version
                    } else null,
                )
                zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
                zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(PREFERENCES_JSON))
                zip.write(json.encodeToString(preferences).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                if (stickersJson != null) {
                    zip.putNextEntry(ZipEntry(STICKERS_JSON))
                    zip.write(stickersJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        if (folder == FileFolders.WORKSPACES) {
                            zip.putNextEntry(ZipEntry(WorkspaceBackup.ARCHIVE_METADATA))
                            zip.write(WorkspaceBackup.capture(directory, json).toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                        val files = when {
                            folder == FileFolders.WORKSPACES -> WorkspaceBackup.regularFiles(directory).asSequence()
                            folder in NESTED_ATTACHMENT_FOLDERS -> directory.walkTopDown().asSequence()
                            else -> directory.listFiles().orEmpty().asSequence()
                        }
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                BACKUP_MANIFEST -> File(staging, BACKUP_MANIFEST)
                                WorkspaceBackup.ARCHIVE_METADATA -> if (includeFiles) {
                                    File(staging, WorkspaceBackup.ARCHIVE_METADATA)
                                } else null
                                PREFERENCES_JSON -> File(staging, PREFERENCES_JSON)
                                STICKERS_JSON -> if (includeFiles) File(staging, STICKERS_JSON) else null
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val manifestFile = File(staging, BACKUP_MANIFEST)
                    if (manifestFile.exists()) {
                        val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText())
                        require(manifest.formatVersion == BackupManifest.FORMAT_VERSION) {
                            "Unsupported backup format version: ${manifest.formatVersion}"
                        }
                        require(manifest.components.settings == ("settings.json" in seen)) {
                            "Backup manifest settings component does not match archive"
                        }
                        require(manifest.components.database == (DatabaseBackup.ARCHIVE_DATABASE in seen)) {
                            "Backup manifest database component does not match archive"
                        }
                        require(manifest.components.preferences == (PREFERENCES_JSON in seen)) {
                            "Backup manifest preferences component does not match archive"
                        }
                        require(manifest.components.stickers == (STICKERS_JSON in seen)) {
                            "Backup manifest stickers component does not match archive"
                        }
                        require(manifest.components.files || seen.none { name ->
                            name == WorkspaceBackup.ARCHIVE_METADATA ||
                                ATTACHMENT_FOLDERS.any { name.startsWith("$it/") }
                        }) { "Backup manifest excludes files but archive contains attachments" }
                        require(!manifest.components.stickers || manifest.components.files) {
                            "Sticker configuration requires file component"
                        }
                    }
                    val preferencesFile = File(staging, PREFERENCES_JSON)
                    if (preferencesFile.exists()) {
                        BackupFormat.validatePreferences(
                            json.decodeFromString<BackupPreferences>(preferencesFile.readText()), json,
                        )
                    }
                    val stickersFile = File(staging, STICKERS_JSON)
                    if (stickersFile.exists()) {
                        BackupFormat.validateStickersJson(
                            File(payload, "files/${FileFolders.STICKERS}"),
                            json,
                            stickersFile.readText(),
                        )
                    }
                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                        val settings = json.decodeFromString<Settings>(migrated)
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeSettings(settings, json.launchCountOf(migrated)))
                    }
                    val workspaceMetadata = File(staging, WorkspaceBackup.ARCHIVE_METADATA)
                    if (workspaceMetadata.isFile) {
                        WorkspaceBackup.restoreMetadata(
                            File(payload, "files/${FileFolders.WORKSPACES}"), json, workspaceMetadata.readText(),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    PendingRestore.discardStaging(staging)
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        const val BACKUP_FILE_PREFIX = "beacon-backup-"

        /** Remote listing only; local restore validates ZIP contents, not its filename. */
        fun isBackupFileName(name: String): Boolean = name.endsWith(".zip") &&
            listOf(BACKUP_FILE_PREFIX, "rikkahub-backup-", "rikkahub_backup_", "backup_")
                .any { name.startsWith(it) }

        private val ATTACHMENT_FOLDERS = listOf(
            FileFolders.UPLOAD,
            FileFolders.SKILLS,
            FileFolders.FONTS,
            FileFolders.MEDIA_CREATION,
            FileFolders.STICKERS,
            FileFolders.IMAGES,
            FileFolders.WORKSPACES,
        )

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(
            FileFolders.SKILLS,
            FileFolders.MEDIA_CREATION,
            FileFolders.WORKSPACES,
        )

        private const val BACKUP_MANIFEST = "backup_manifest.json"
        private const val PREFERENCES_JSON = "preferences.json"
        private const val STICKERS_JSON = "stickers.json"

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply(
                preparePreferences = { pending ->
                    if (File(pending, "settings.json").isFile) {
                        val original = File(pending, "settings-original.json")
                        if (!original.exists()) {
                            val (settings, launchCount) = SettingsStore.captureBeforeInitialization(context)
                            PendingRestore.writeDurably(original, json.encodeSettings(settings, launchCount))
                        } else {
                            json.decodeFromString<Settings>(original.readText())
                        }
                    }
                    BackupFormat.preparePreferenceRollback(context, pending, json)
                },
                rollbackPreferences = { pending ->
                    BackupFormat.rollbackPreferences(context, pending, json)
                    val original = File(pending, "settings-original.json")
                    if (original.isFile) {
                        val text = original.readText()
                        SettingsStore.restoreBeforeInitialization(
                            context, json.decodeFromString<Settings>(text), json.launchCountOf(text),
                        )
                    }
                },
                restoreSettings = { settingsJson ->
                    SettingsStore.restoreBeforeInitialization(
                        context = context,
                        settings = json.decodeFromString<Settings>(settingsJson),
                        launchCount = json.launchCountOf(settingsJson),
                    )
                },
                restorePreferences = { preferencesJson ->
                    BackupFormat.restorePreferences(
                        context = context,
                        value = json.decodeFromString<BackupPreferences>(preferencesJson),
                        json = json,
                    )
                },
                restoreStickers = { stickersJson ->
                    BackupFormat.restoreStickers(
                        context = context,
                        stickerDirectory = File(context.filesDir, FileFolders.STICKERS),
                        json = json,
                        text = stickersJson,
                    )
                },
            )
        }
    }
}
