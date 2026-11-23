package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Sticker
import me.rerere.rikkahub.data.datastore.StickerDisplaySize
import me.rerere.rikkahub.data.datastore.StickerGroup
import me.rerere.rikkahub.data.datastore.UsageBudgetState
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager
    private val sharedPreferencesNames = mutableSetOf<String>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        val preferencesPrefix = "backup-manager-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = preferencesPrefix + name
                sharedPreferencesNames.add(isolatedName)
                return app.getSharedPreferences(isolatedName, mode)
            }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
    }

    @After fun tearDown() {
        try {
            if (::liveDatabase.isInitialized) liveDatabase.close()
        } finally {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            sharedPreferencesNames.forEach { name ->
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                app.deleteSharedPreferences(name)
            }
            sharedPreferencesNames.clear()
            if (::directory.isInitialized) deleteWithoutFollowingLinks(directory)
        }
    }

    @Test fun oldArchiveIgnoresShmAndLeavesLiveDatabaseUntouchedUntilStartup() = runBlocking {
        val general = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        val budget = context.getSharedPreferences("usage_budget", Context.MODE_PRIVATE)
        val stickers = context.getSharedPreferences("url_stickers", Context.MODE_PRIVATE)
        assertTrue(general.edit().putString("colorMode", "DARK")
            .putString("lastConversationId", "current-conversation").commit())
        assertTrue(budget.edit().putString("state", JsonInstant.encodeToString(
            UsageBudgetState(month = "2026-11", budget = "123", monthlyCosts = mapOf("2026-11" to "4.5"))
        )).commit())
        assertTrue(stickers.edit().putString("groups", "[]")
            .putString("display_size", StickerDisplaySize.SMALL.name).commit())
        val originalGeneral = general.all
        val originalBudget = budget.all
        val originalStickers = stickers.all
        val source = File(directory, "source")
        val archive = File(directory, "legacy.zip")
        withRoom(source) { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            DatabaseBackup.checkpoint(db)
            db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
                addFile(zip, DatabaseBackup.WAL, File(source.path + "-wal"))
                zip.putNextEntry(ZipEntry(DatabaseBackup.SHM))
                zip.write("deliberately invalid SHM".toByteArray())
                zip.closeEntry()
            }
        }
        assertFalse(manager.hasPendingRestore())
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertTrue(manager.hasPendingRestore())
        val recreatedManager = BackupManager(
            context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant,
        )
        assertTrue(recreatedManager.hasPendingRestore())
        assertEquals("live", probe(liveDatabase))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertFalse(recreatedManager.hasPendingRestore())
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("archived", probe(liveDatabase))
        assertEquals(originalGeneral, general.all)
        assertEquals(originalBudget, budget.all)
        assertEquals(originalStickers, stickers.all)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry(DatabaseBackup.SHM) != null)
            assertEquals(null, zip.getEntry("backup_manifest.json"))
            assertEquals(null, zip.getEntry("preferences.json"))
            assertEquals(null, zip.getEntry("stickers.json"))
        }
    }

    @Test fun legacySchema35ArchiveWithExistingStorageMountsMigratesDuringStaging() = runBlocking {
        val source = File(directory, "schema35-with-storage-mounts")
        val mounts = """[{"id":"shared","path":"/mnt/shared"}]"""
        // 从真实旧 schema 建库，只预先添加曾导致恢复时重复加列的 storage_mounts。
        migrationHelper.createDatabase(source.absolutePath, 35).use { db ->
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `storage_mounts` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, storage_mounts) " +
                    "VALUES ('archived-workspace', 'Archived workspace', 'archived-root', 'READY', 100, 200, ?)",
                arrayOf<Any>(mounts),
            )
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            db.execSQL("INSERT INTO backup_probe VALUES ('archived schema35')")
            assertEquals(35, db.version)
            DatabaseBackup.checkpoint(db)
        }
        DatabaseBackup.removeSidecars(source)
        assertEquals(35, DatabaseBackup.readUserVersion(context, source))
        val archive = File(directory, "legacy-schema35.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
        }
        ZipFile(archive).use { zip ->
            assertEquals(listOf(DatabaseBackup.ARCHIVE_DATABASE), zip.entries().asSequence().map { it.name }.toList())
        }

        assertFalse(manager.hasPendingRestore())
        assertEquals("live", probe(liveDatabase))
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertTrue(manager.hasPendingRestore())
        assertEquals("live", probe(liveDatabase))
        assertEquals(35, DatabaseBackup.readUserVersion(context, source))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertEquals(39, DatabaseBackup.readUserVersion(context, staged))
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())

        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertFalse(manager.hasPendingRestore())
        liveDatabase = AppDatabaseFactory.create(context)
        val restored = liveDatabase.openHelper.readableDatabase
        assertEquals(39, restored.version)
        assertEquals("archived schema35", probe(liveDatabase))
        restored.query(
            "SELECT name, root, storage_mounts FROM workspaces WHERE id = 'archived-workspace'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Archived workspace", cursor.getString(0))
            assertEquals("archived-root", cursor.getString(1))
            assertEquals(mounts, cursor.getString(2))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test fun newArchiveContainsStandaloneDatabaseAndNoWalOrShm() = runBlocking {
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            val manifest = JsonInstant.decodeFromString<BackupManifest>(
                zip.getInputStream(zip.getEntry("backup_manifest.json")).bufferedReader().use { it.readText() }
            )
            assertEquals(2, manifest.formatVersion)
            assertEquals(BackupManifestComponents(database = true), manifest.components)
            assertEquals(liveDatabase.openHelper.readableDatabase.version, manifest.databaseUserVersion)
            assertTrue(zip.getEntry("preferences.json") != null)
            assertEquals(null, zip.getEntry("stickers.json"))
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun exportFallsBackToFileSnapshotWhenRoomRejectsDatabaseVersion() = runBlocking {
        val source = context.getDatabasePath("rikka_hub")
        val attachments = File(context.filesDir, "images/recovery.png").apply {
            parentFile!!.mkdirs()
            writeText("recovery attachment")
        }
        liveDatabase.openHelper.writableDatabase.version = 9999
        liveDatabase.close()
        liveDatabase = AppDatabaseFactory.create(context)
        val roomFailure = runCatching { liveDatabase.openHelper.writableDatabase }.exceptionOrNull()
        assertTrue("Room 应拒绝未知版本", roomFailure != null)
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
        val archive = manager.createBackup(includeDatabase = true, includeFiles = true)
        try {
            ZipFile(archive).use { zip ->
                val manifest = JsonInstant.decodeFromString<BackupManifest>(
                    zip.getInputStream(zip.getEntry("backup_manifest.json")).bufferedReader().use { it.readText() }
                )
                assertEquals(9999, manifest.databaseUserVersion)
                assertTrue(manifest.components.database)
                assertTrue(manifest.components.files)
                assertTrue(zip.getEntry("settings.json") != null)
                assertTrue(zip.getEntry("preferences.json") != null)
                assertTrue(zip.getEntry("workspace_metadata.json") != null)
                assertEquals("recovery attachment", zip.getInputStream(zip.getEntry("images/recovery.png"))
                    .bufferedReader().use { it.readText() })
                val snapshot = File(directory, "recovery-snapshot")
                zip.getInputStream(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE)).use { input ->
                    snapshot.outputStream().use { input.copyTo(it) }
                }
                assertEquals(9999, DatabaseBackup.readUserVersion(context, snapshot))
                assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
                assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
            }
            assertEquals(9999, DatabaseBackup.readUserVersion(context, source))
            assertEquals("recovery attachment", attachments.readText())
        } finally {
            archive.delete()
        }
    }

    @Test fun rescueExportRejectsCorruptionWithoutOpeningRoomOrDeletingSource() = runBlocking {
        liveDatabase.close()
        val source = context.getDatabasePath("rikka_hub")
        val original = "not a SQLite database: preserve recovery evidence".toByteArray()
        source.writeBytes(original)
        liveDatabase = AppDatabaseFactory.create(context)
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
        val cacheBefore = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()

        val failure = runCatching { manager.createRescueBackup() }.exceptionOrNull()

        assertTrue("救援备份必须明确失败，不能导出重建的空库", failure != null)
        assertFalse("救援备份不能打开 Room", liveDatabase.isOpen)
        assertTrue(source.isFile)
        assertArrayEquals(original, source.readBytes())
        assertEquals(cacheBefore, context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
        assertFalse(manager.hasPendingRestore())
    }

    @Test fun rescueExportPreservesFutureSchemaWithoutOpeningRoom() = runBlocking {
        val source = context.getDatabasePath("rikka_hub")
        liveDatabase.openHelper.writableDatabase.version = 9999
        liveDatabase.close()
        val original = source.readBytes()
        liveDatabase = AppDatabaseFactory.create(context)
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
        val archive = manager.createRescueBackup()
        try {
            assertFalse(liveDatabase.isOpen)
            assertArrayEquals(original, source.readBytes())
            ZipFile(archive).use { zip ->
                val manifest = JsonInstant.decodeFromString<BackupManifest>(
                    zip.getInputStream(zip.getEntry("backup_manifest.json")).bufferedReader().use { it.readText() }
                )
                assertEquals(9999, manifest.databaseUserVersion)
                assertTrue(manifest.components.database)
                assertTrue(manifest.components.files)
                assertTrue(zip.getEntry("settings.json") != null)
                assertTrue(zip.getEntry("preferences.json") != null)
                assertTrue(zip.getEntry("workspace_metadata.json") != null)
                val snapshot = File(directory, "rescue-future-snapshot")
                zip.getInputStream(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE)).use { input ->
                    snapshot.outputStream().use { input.copyTo(it) }
                }
                assertEquals(9999, DatabaseBackup.readUserVersion(context, snapshot))
                assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
                assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
            }
        } finally {
            archive.delete()
        }
    }

    @Test fun rescueExportIncludesCommittedWalAndKeepsFullBeaconArchive() = runBlocking {
        val db = liveDatabase.openHelper.writableDatabase
        DatabaseBackup.checkpoint(db)
        db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        db.execSQL("INSERT INTO backup_probe VALUES ('rescue WAL')")
        assertTrue(File(context.getDatabasePath("rikka_hub").path + "-wal").length() > 0)
        val attachment = File(context.filesDir, "images/rescue.png").apply {
            parentFile!!.mkdirs()
            writeText("rescue attachment")
        }
        val archive = manager.createRescueBackup()
        try {
            db.execSQL("INSERT INTO backup_probe VALUES ('after rescue')")
            ZipFile(archive).use { zip ->
                assertEquals("rescue attachment", zip.getInputStream(zip.getEntry("images/rescue.png"))
                    .bufferedReader().use { it.readText() })
                assertTrue(zip.getEntry("settings.json") != null)
                assertTrue(zip.getEntry("preferences.json") != null)
                assertTrue(zip.getEntry("workspace_metadata.json") != null)
                val snapshot = File(directory, "rescue-wal-snapshot")
                zip.getInputStream(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE)).use { input ->
                    snapshot.outputStream().use { input.copyTo(it) }
                }
                withRoom(snapshot) { room ->
                    room.openHelper.readableDatabase.query("SELECT text FROM backup_probe ORDER BY rowid").use { cursor ->
                        val values = mutableListOf<String>()
                        while (cursor.moveToNext()) values.add(cursor.getString(0))
                        assertEquals(listOf("live", "rescue WAL"), values)
                    }
                }
                assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
                assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
            }
            assertEquals("rescue attachment", attachment.readText())
        } finally {
            archive.delete()
        }
    }

    @Test fun memoryBodyAndFourSideTablesSurviveFullZipRestoreWithAnotherPendingJob() = runBlocking {
        val namespace = "backup-assistant"
        val file = MemoryFileEntity(namespace, "notes/indexed.md", "第一段记忆\n第二段记忆", 101L)
        val metadata = MemoryMetadataEntity(
            assistantId = namespace,
            path = file.path,
            scopeKind = "ASSISTANT",
            category = "USER_FACT",
            lifecycle = "ACTIVE",
            contentHash = memoryContentHash(file.content),
            revision = "backup-indexed-revision",
            updatedAt = file.updatedAt,
        )
        val provenance = MemorySource(
            kind = MemorySourceKind.GROUP_CHAT,
            conversationId = "backup-conversation",
            messageId = "backup-message",
            groupId = "backup-group",
            speakerId = "backup-speaker",
            participantIds = listOf(namespace, "another-participant"),
            sourceContentHash = memoryContentHash("来源引用"),
            occurredAt = 99L,
        )
        provenance.validate()
        val sources = listOf(
            MemorySourceEntity(
                assistantId = namespace,
                path = file.path,
                sourceId = "a-chat-source",
                kind = provenance.kind.name,
                memoryRevision = metadata.revision,
                conversationId = provenance.conversationId,
                messageId = provenance.messageId,
                sourceJson = JsonInstant.encodeToString(provenance),
            ),
            MemorySourceEntity(
                assistantId = namespace,
                path = file.path,
                sourceId = "b-stale-source",
                kind = MemorySourceKind.MANUAL.name,
                status = "STALE",
                memoryRevision = "older-source-revision",
                sourceJson = JsonInstant.encodeToString(MemorySource(kind = MemorySourceKind.MANUAL)),
            ),
        )
        val chunks = listOf("第一段记忆", "第二段记忆").mapIndexed { index, content ->
            MemoryChunkEntity(
                assistantId = namespace,
                path = file.path,
                chunkIndex = index,
                memoryRevision = metadata.revision,
                contentHash = metadata.contentHash,
                chunkerVersion = 2,
                content = content,
                embedding = ByteBuffer.allocate(2 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(index + 0.25f).putFloat(-index - 0.5f).array(),
                embeddingProfile = "backup-embedding-profile",
                embeddingDimensions = 2,
            )
        }
        fun assertChunks(actual: List<MemoryChunkEntity>) {
            // ByteArray 的 data class 相等判断比较引用，BLOB 必须逐字节核对。
            assertEquals(chunks.map { it.copy(embedding = null) }, actual.map { it.copy(embedding = null) })
            chunks.zip(actual).forEach { (expected, restoredChunk) ->
                assertArrayEquals(expected.embedding, restoredChunk.embedding)
                assertEquals("backup-embedding-profile", restoredChunk.embeddingProfile)
                assertEquals(2, restoredChunk.embeddingDimensions)
            }
        }
        val pendingFile = MemoryFileEntity(namespace, "notes/pending.md", "尚待索引的另一份记忆", 202L)
        val pendingMetadata = metadata.copy(
            path = pendingFile.path,
            category = "ROLE_EXPERIENCE",
            contentHash = memoryContentHash(pendingFile.content),
            revision = "backup-pending-revision",
            updatedAt = pendingFile.updatedAt,
        )
        val pendingSources = listOf(sources.first().copy(
            path = pendingFile.path,
            sourceId = "pending-source",
            memoryRevision = pendingMetadata.revision,
        ))
        val pendingJob = MemoryIndexJobEntity(
            namespace, pendingFile.path, pendingMetadata.revision, pendingMetadata.contentHash, pendingFile.updatedAt,
        )
        val dao = liveDatabase.memoryDao()
        dao.saveState(file, metadata, sources)
        dao.saveState(pendingFile, pendingMetadata, pendingSources)
        val indexedJob = dao.getIndexJobs(namespace).single { it.path == file.path }
        assertTrue(dao.publishChunks(indexedJob, chunks))
        assertEquals(listOf(pendingJob), dao.getIndexJobs(namespace))
        assertChunks(dao.getChunks(namespace, file.path))

        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertTrue(zip.getEntry("settings.json") != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        // 删除当前库内容，确保恢复断言不能误读仍留在内存或磁盘中的原始数据。
        dao.deleteFilesOfAssistant(namespace)
        assertTrue(dao.getFilesOfAssistant(namespace).isEmpty())
        assertTrue(dao.getIndexJobs(namespace).isEmpty())
        listOf(file, pendingFile).forEach { entry ->
            assertEquals(null, dao.getMetadata(namespace, entry.path))
            assertTrue(dao.getSources(namespace, entry.path).isEmpty())
            assertTrue(dao.getChunks(namespace, entry.path).isEmpty())
        }

        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertTrue(dao.getFilesOfAssistant(namespace).isEmpty())
        val pending = File(context.noBackupFilesDir, "backup-restore/pending")
        assertTrue(File(pending, "payload/database/rikka_hub").isFile)
        // 不应用 settings，避免写入被测应用的真实 SettingsStore。
        assertTrue(File(pending, "settings.json").delete())
        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        liveDatabase = AppDatabaseFactory.create(context)
        val restored = liveDatabase.memoryDao()

        assertEquals(listOf(file, pendingFile), restored.getFilesOfAssistant(namespace))
        assertEquals(file, restored.getFile(namespace, file.path))
        assertEquals(metadata, restored.getMetadata(namespace, file.path))
        assertEquals(sources, restored.getSources(namespace, file.path))
        assertEquals(provenance, JsonInstant.decodeFromString<MemorySource>(
            restored.getSources(namespace, file.path).first().sourceJson,
        ))
        assertChunks(restored.getChunks(namespace, file.path))
        assertEquals(pendingFile, restored.getFile(namespace, pendingFile.path))
        assertEquals(pendingMetadata, restored.getMetadata(namespace, pendingFile.path))
        assertEquals(pendingSources, restored.getSources(namespace, pendingFile.path))
        assertTrue(restored.getChunks(namespace, pendingFile.path).isEmpty())
        assertEquals(listOf(pendingJob), restored.getIndexJobs(namespace))
        liveDatabase.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse("ZIP 往返后不应留下孤立的记忆旁表行", it.moveToFirst())
        }
        assertFalse(pending.exists())
    }

    @Test fun filesDisabledExportsOnlySettingsManifestAndWhitelistedPreferences() = runBlocking {
        val general = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        val budget = context.getSharedPreferences("usage_budget", Context.MODE_PRIVATE)
        val budgetState = JsonInstant.encodeToString(
            UsageBudgetState(month = "2026-11", budget = "321", monthlyCosts = mapOf("2026-11" to "12.34"))
        )
        assertTrue(general.edit()
            .putString("colorMode", "DARK")
            .putBoolean("amoledDark", false)
            .putBoolean("create_new_conversation_on_start", true)
            .putString("model_list_collapsed_providers", "[\"provider-id\"]")
            .putString("search_page_sort_order", "oldest")
            .putString("lastConversationId", "archived-conversation")
            .putString("not_backed_up", "archived-value")
            .commit())
        assertTrue(budget.edit().putString("state", budgetState)
            .putString("not_backed_up", "archived-budget-value").commit())
        assertTrue(context.getSharedPreferences("url_stickers", Context.MODE_PRIVATE).edit()
            .putString("groups", "[]").putString("display_size", StickerDisplaySize.LARGE.name).commit())
        listOf("images/photo.png", "workspaces/project/nested/file.txt", "stickers/unused.png").forEach { path ->
            File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText("excluded") }
        }

        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("settings.json", "backup_manifest.json", "preferences.json"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val manifest = JsonInstant.decodeFromString<BackupManifest>(
                zip.getInputStream(zip.getEntry("backup_manifest.json")).bufferedReader().use { it.readText() }
            )
            assertEquals(2, manifest.formatVersion)
            assertEquals(BackupManifestComponents(), manifest.components)
            assertEquals(null, manifest.databaseUserVersion)
            val text = zip.getInputStream(zip.getEntry("preferences.json")).bufferedReader().use { it.readText() }
            assertEquals(
                setOf("schemaVersion", "usageBudgetState", "colorMode", "amoledDark",
                    "createNewConversationOnStart", "modelListCollapsedProviders", "searchPageSortOrder"),
                JsonInstant.parseToJsonElement(text).jsonObject.keys,
            )
            assertEquals(
                BackupPreferences(
                    usageBudgetState = budgetState,
                    colorMode = "DARK",
                    amoledDark = false,
                    createNewConversationOnStart = true,
                    modelListCollapsedProviders = "[\"provider-id\"]",
                    searchPageSortOrder = "oldest",
                ),
                JsonInstant.decodeFromString<BackupPreferences>(text),
            )
            assertFalse(text.contains("lastConversationId"))
            assertFalse(text.contains("archived-conversation"))
            assertFalse(text.contains("not_backed_up"))
        }

        assertTrue(general.edit().clear().putString("lastConversationId", "current-conversation")
            .putString("not_backed_up", "current-value").commit())
        assertTrue(budget.edit().clear().putString("not_backed_up", "current-budget-value").commit())
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        // 不应用 settings，避免写入被测应用的真实 SettingsStore。
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("DARK", general.getString("colorMode", null))
        assertTrue(general.contains("amoledDark"))
        assertFalse(general.getBoolean("amoledDark", true))
        assertTrue(general.getBoolean("create_new_conversation_on_start", false))
        assertEquals("[\"provider-id\"]", general.getString("model_list_collapsed_providers", null))
        assertEquals("oldest", general.getString("search_page_sort_order", null))
        assertEquals(budgetState, budget.getString("state", null))
        assertEquals("current-conversation", general.getString("lastConversationId", null))
        assertEquals("current-value", general.getString("not_backed_up", null))
        assertEquals("current-budget-value", budget.getString("not_backed_up", null))
    }

    @Test fun filesEnabledRestoresImagesNestedWorkspacesAndRebuildsLocalStickerUrls() = runBlocking {
        val stickerFile = File(context.filesDir, "stickers/${UUID.randomUUID()}.png")
            .apply { parentFile!!.mkdirs(); writeText("sticker") }
        val groups = listOf(StickerGroup(
            id = UUID.randomUUID().toString(),
            name = "测试分组",
            stickers = listOf(Sticker(
                id = UUID.randomUUID().toString(),
                url = Uri.fromFile(stickerFile).toString(),
                name = "本地表情",
            )),
        ))
        val stickers = context.getSharedPreferences("url_stickers", Context.MODE_PRIVATE)
        assertTrue(stickers.edit().putString("groups", JsonInstant.encodeToString(groups))
            .putString("display_size", StickerDisplaySize.LARGE.name).commit())
        val files = mapOf(
            "images/photo.png" to "image",
            "workspaces/project/nested/file.txt" to "workspace",
            "stickers/${stickerFile.name}" to "sticker",
        )
        files.forEach { (path, content) ->
            File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText(content) }
        }
        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            val manifest = JsonInstant.decodeFromString<BackupManifest>(
                zip.getInputStream(zip.getEntry("backup_manifest.json")).bufferedReader().use { it.readText() }
            )
            assertEquals(2, manifest.formatVersion)
            assertEquals(BackupManifestComponents(stickers = true, files = true), manifest.components)
            assertEquals(null, manifest.databaseUserVersion)
            files.forEach { (path, content) ->
                assertEquals(content, zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() })
            }
            val text = zip.getInputStream(zip.getEntry("stickers.json")).bufferedReader().use { it.readText() }
            val snapshot = JsonInstant.decodeFromString<BackupStickerSnapshot>(text)
            assertEquals(1, snapshot.schemaVersion)
            assertEquals(StickerDisplaySize.LARGE.name, snapshot.displaySize)
            assertEquals(groups.map { group ->
                group.copy(stickers = group.stickers.map { it.copy(url = stickerFile.name) })
            }, snapshot.groups)
            assertFalse(text.contains("file://"))
            assertFalse(text.contains(context.filesDir.absolutePath))
        }

        val restoredContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "restored-files").apply { mkdirs() }
        }
        val restoredManager = BackupManager(
            restoredContext, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant
        )
        assertTrue(stickers.edit().clear().commit())
        restoredManager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        files.keys.forEach { assertFalse(File(restoredContext.filesDir, it).exists()) }
        assertFalse(stickers.contains("groups"))
        // 不应用 settings，避免写入被测应用的真实 SettingsStore。
        assertTrue(File(restoredContext.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(restoredContext, JsonInstant))
        files.forEach { (path, content) ->
            assertEquals(content, File(restoredContext.filesDir, path).readText())
        }
        val restoredFile = File(restoredContext.filesDir, "stickers/${stickerFile.name}")
        val restoredGroups = JsonInstant.decodeFromString<List<StickerGroup>>(stickers.getString("groups", null)!!)
        assertEquals(groups.map { group ->
            group.copy(stickers = group.stickers.map { it.copy(url = Uri.fromFile(restoredFile).toString()) })
        }, restoredGroups)
        assertEquals(StickerDisplaySize.LARGE.name, stickers.getString("display_size", null))
        assertFalse(restoredGroups.single().stickers.single().url == groups.single().stickers.single().url)
    }

    @Test fun workspaceRootfsSurvivesBackupStagingAndMergeWithoutFollowingAbsoluteLinks() = runBlocking {
        val workspacePath = "workspaces/${UUID.randomUUID()}"
        val workspace = File(context.filesDir, workspacePath)
        val emptyPaths = listOf("files/linux/tmp", "linux/tmp")
        emptyPaths.forEach { assertTrue(File(workspace, it).mkdirs()) }
        val busybox = File(workspace, "linux/bin/busybox").apply {
            parentFile!!.mkdirs()
            writeText("archived executable")
        }
        val permissions = PosixFilePermissions.fromString("rwxr-x--x")
        Files.setPosixFilePermissions(busybox.toPath(), permissions)
        val shell = File(workspace, "linux/bin/sh").toPath()
        Files.createSymbolicLink(shell, File("/bin/busybox").toPath())

        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("workspace_metadata.json") != null)
            assertEquals("archived executable", zip.getInputStream(zip.getEntry("$workspacePath/linux/bin/busybox"))
                .bufferedReader().use { it.readText() })
            // 链接只存在于元数据中，不能被当成 host 普通文件读取。
            assertEquals(null, zip.getEntry("$workspacePath/linux/bin/sh"))
            emptyPaths.forEach { assertEquals(null, zip.getEntry("$workspacePath/$it/")) }
        }

        val restoredContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "restored-files").apply { mkdirs() }
        }
        val restoredWorkspace = File(restoredContext.filesDir, workspacePath)
        val existing = File(restoredWorkspace, "linux/bin/busybox").apply {
            parentFile!!.mkdirs()
            writeText("live executable")
        }
        val missingFromArchive = File(restoredWorkspace, "files/live-only.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep live file")
        }
        val untouchedWorkspace = File(restoredContext.filesDir, "workspaces/live-only/files/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep live workspace")
        }
        val restoredManager = BackupManager(
            restoredContext, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant
        )
        restoredManager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        val pending = File(restoredContext.noBackupFilesDir, "backup-restore/pending")
        val stagedWorkspace = File(pending, "payload/files/$workspacePath")
        fun assertRootfs(root: File) {
            emptyPaths.forEach { path ->
                val empty = File(root, path).toPath()
                assertTrue(Files.isDirectory(empty, NOFOLLOW_LINKS))
                Files.newDirectoryStream(empty).use { assertFalse(it.iterator().hasNext()) }
            }
            val link = File(root, "linux/bin/sh").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("/bin/busybox", Files.readSymbolicLink(link).toString())
            val executable = File(root, "linux/bin/busybox").toPath()
            assertTrue(Files.isRegularFile(executable, NOFOLLOW_LINKS))
            assertEquals("archived executable", executable.toFile().readText())
            assertEquals(permissions, Files.getPosixFilePermissions(executable, NOFOLLOW_LINKS))
        }
        assertRootfs(stagedWorkspace)
        assertEquals("live executable", existing.readText())
        assertEquals("keep live file", missingFromArchive.readText())
        assertFalse(Files.exists(File(restoredWorkspace, "linux/bin/sh").toPath(), NOFOLLOW_LINKS))
        assertFalse(File(stagedWorkspace, "files/live-only.txt").exists())
        // 不应用 settings，避免写入被测应用的全局 DataStore。
        assertTrue(File(pending, "settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(restoredContext, JsonInstant))

        assertRootfs(restoredWorkspace)
        assertEquals("keep live file", missingFromArchive.readText())
        assertEquals("keep live workspace", untouchedWorkspace.readText())
        assertFalse(pending.exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun unsupportedManifestAndMissingDeclaredComponentsAreRejectedBeforePublishing() = runBlocking {
        val components = BackupManifestComponents(settings = false, files = true)
        val manifest = BackupManifest(components = components)
        val cases = listOf(
            Triple(manifest.copy(formatVersion = 999), false, "Unsupported backup format version"),
            Triple(manifest.copy(components = components.copy(settings = true)), false, "settings component"),
            Triple(manifest.copy(components = components.copy(database = true)), false, "database component"),
            Triple(manifest, true, "preferences component"),
            Triple(manifest.copy(components = components.copy(stickers = true)), false, "stickers component"),
        )
        val existing = File(context.filesDir, "images/photo.png").apply {
            parentFile!!.mkdirs()
            writeText("live image")
        }
        cases.forEach { (invalid, omitPreferences, expectedMessage) ->
            val archive = File(directory, "invalid-manifest.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addText(zip, "backup_manifest.json", JsonInstant.encodeToString(invalid))
                if (!omitPreferences) addText(zip, "preferences.json", JsonInstant.encodeToString(BackupPreferences()))
                addText(zip, "images/photo.png", "replacement")
            }
            val failure = runCatching {
                manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
            }.exceptionOrNull()
            assertTrue("应拒绝 manifest：$invalid，实际异常：$failure", failure is IllegalArgumentException)
            assertTrue("实际异常：$failure", failure?.message?.contains(expectedMessage) == true)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertEquals("live image", existing.readText())
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun attachmentPathTraversalIsRejectedBeforePublishing() = runBlocking {
        val existing = File(context.filesDir, "images/photo.png").apply {
            parentFile!!.mkdirs()
            writeText("live image")
        }
        listOf(
            "images/../escape.txt",
            "workspaces/../../escape.txt",
            "workspaces/project/../../../escape.txt",
            "media_creation/session/../../../../escape.txt",
        ).forEach { path ->
            val archive = File(directory, "path-traversal.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addText(zip, "images/photo.png", "replacement")
                addText(zip, path, "must not escape")
            }
            val failure = runCatching {
                manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
            }.exceptionOrNull()
            // 新版 Android 也可能在 ZipFile 打开时直接拒绝越界路径。
            assertTrue("应拒绝附件路径：$path，实际异常：$failure",
                failure is IllegalArgumentException || failure is java.util.zip.ZipException)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertEquals("live image", existing.readText())
            assertFalse(File(directory, "escape.txt").exists())
            assertFalse(File(context.filesDir, "escape.txt").exists())
            assertFalse(File(context.noBackupFilesDir, "backup-restore/escape.txt").exists())
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun duplicateAttachmentEntriesAreRejectedBeforePublishing() = runBlocking {
        val archive = File(directory, "duplicate-entries.zip")
        val firstName = "images/first.png"
        val secondName = "images/other.png"
        ZipOutputStream(archive.outputStream()).use { zip ->
            addText(zip, firstName, "first")
            addText(zip, secondName, "second")
        }
        val bytes = archive.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // 仅修改中央目录及其引用的 local header 中的等长文件名，不解码整个 ZIP 或改动压缩数据。
        val end = bytes.size - 22
        assertEquals(0x06054b50, buffer.getInt(end))
        assertEquals(0, buffer.getShort(end + 20).toInt())
        assertEquals(2, buffer.getShort(end + 10).toInt())
        val central = buffer.getInt(end + 16)
        assertEquals(0x02014b50, buffer.getInt(central))
        val secondCentral = central + 46 + (buffer.getShort(central + 28).toInt() and 0xffff) +
            (buffer.getShort(central + 30).toInt() and 0xffff) + (buffer.getShort(central + 32).toInt() and 0xffff)
        assertEquals(0x02014b50, buffer.getInt(secondCentral))
        val secondLocal = buffer.getInt(secondCentral + 42)
        assertEquals(0x04034b50, buffer.getInt(secondLocal))
        val replacement = firstName.toByteArray(Charsets.UTF_8)
        assertEquals(replacement.size, buffer.getShort(secondCentral + 28).toInt())
        assertEquals(replacement.size, buffer.getShort(secondLocal + 26).toInt())
        assertEquals(secondName, bytes.copyOfRange(secondCentral + 46, secondCentral + 46 + replacement.size)
            .toString(Charsets.UTF_8))
        assertEquals(secondName, bytes.copyOfRange(secondLocal + 30, secondLocal + 30 + replacement.size)
            .toString(Charsets.UTF_8))
        replacement.copyInto(bytes, secondCentral + 46)
        replacement.copyInto(bytes, secondLocal + 30)
        archive.writeBytes(bytes)
        ZipFile(archive).use { zip ->
            assertEquals(listOf(firstName, firstName), zip.entries().asSequence().map { it.name }.toList())
        }
        val existing = File(context.filesDir, firstName).apply {
            parentFile!!.mkdirs()
            writeText("live image")
        }
        val failure = runCatching {
            manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        }.exceptionOrNull()
        assertTrue("应拒绝重复 ZIP 条目，实际异常：$failure", failure is IllegalArgumentException)
        assertTrue("实际异常：$failure", failure?.message?.contains("Duplicate backup entry") == true)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live image", existing.readText())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun malformedPreferencesAreRejectedBeforePublishing() = runBlocking {
        val general = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        assertTrue(general.edit().putString("colorMode", "LIGHT").commit())
        listOf("{", """{"amoledDark": {}}""").forEach { text ->
            val archive = File(directory, "invalid-preferences.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addText(zip, "preferences.json", text)
                addText(zip, "images/photo.png", "replacement")
            }
            val failure = runCatching {
                manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
            }.exceptionOrNull()
            assertTrue("应拒绝损坏偏好：$text，实际异常：$failure", failure is IllegalArgumentException)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertFalse(File(context.filesDir, "images/photo.png").exists())
            assertEquals("LIGHT", general.getString("colorMode", null))
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun invalidPreferencesSchemaOrBudgetIsRejectedWithoutChangingPreferences() = runBlocking {
        val general = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        val budget = context.getSharedPreferences("usage_budget", Context.MODE_PRIVATE)
        assertTrue(general.edit().putString("colorMode", "LIGHT").commit())
        val originalBudget = JsonInstant.encodeToString(UsageBudgetState(month = "2026-11"))
        assertTrue(budget.edit().putString("state", originalBudget).commit())
        listOf(
            BackupPreferences(schemaVersion = 9999, colorMode = "DARK"),
            BackupPreferences(usageBudgetState = "{", colorMode = "DARK"),
            BackupPreferences(usageBudgetState = JsonInstant.encodeToString(
                UsageBudgetState(month = "2026-11", budget = "-1")
            ), colorMode = "DARK"),
        ).forEach { preferences ->
            val archive = File(directory, "invalid-preferences-value.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addText(zip, "preferences.json", JsonInstant.encodeToString(preferences))
            }
            val failure = runCatching {
                manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertEquals("LIGHT", general.getString("colorMode", null))
            assertEquals(originalBudget, budget.getString("state", null))
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun invalidStickerPathsAreRejectedBeforePublishing() = runBlocking {
        val fileName = "${UUID.randomUUID()}.png"
        val stickers = context.getSharedPreferences("url_stickers", Context.MODE_PRIVATE)
        assertTrue(stickers.edit().putString("groups", "[]").commit())
        listOf(
            "../$fileName",
            "nested/$fileName",
            "/$fileName",
            "file:///stickers/$fileName",
            "${UUID.randomUUID()}.txt",
            "not-a-uuid.png",
            "${UUID.randomUUID()}.png", // 合法文件名，但归档中缺少对应文件。
        ).forEach { path ->
            val snapshot = BackupStickerSnapshot(groups = listOf(StickerGroup(
                id = UUID.randomUUID().toString(),
                name = "测试分组",
                stickers = listOf(Sticker(id = UUID.randomUUID().toString(), url = path)),
            )))
            val archive = File(directory, "invalid-stickers.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addText(zip, "stickers.json", JsonInstant.encodeToString(snapshot))
                addText(zip, "stickers/$fileName", "sticker")
            }
            val failure = runCatching {
                manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
            }.exceptionOrNull()
            assertTrue("应拒绝表情路径：$path，实际异常：$failure", failure is IllegalArgumentException)
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertFalse(File(context.filesDir, "stickers/$fileName").exists())
            assertEquals("[]", stickers.getString("groups", null))
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun launchCountStaysInSettingsJsonThroughBackupAndStaging() = runBlocking {
        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        val archived = ZipFile(archive).use { zip ->
            launchCountOf(zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString())
        }
        assertTrue(archived != null)
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(archived, launchCountOf(staged))
    }

    @Test fun archiveWithoutLaunchCountRestoresItAsZero() = runBlocking {
        val current = manager.createBackup(includeDatabase = false, includeFiles = false)
        val settingsJson = ZipFile(current).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString()
        }
        val archive = File(directory, "no-launch-count.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(JsonObject(JsonInstant.parseToJsonElement(settingsJson).jsonObject - "launchCount").toString().toByteArray())
            zip.closeEntry()
        }
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(0, launchCountOf(staged))
    }

    private fun launchCountOf(settingsJson: String): Int? =
        JsonInstant.parseToJsonElement(settingsJson).jsonObject["launchCount"]?.jsonPrimitive?.int

    @Test fun mediaCreationFilesKeepTheirDirectoriesAndSkipUnfinishedDownloads() = runBlocking {
        val session = File(context.filesDir, "media_creation/session")
        File(session, "record").mkdirs()
        File(session, "draft").mkdirs()
        File(session, "record/out_0.png").writeText("image")
        File(session, "record/out_1.mp4.part").writeText("half a video")
        File(session, "draft/asset.jpg").writeText("asset")
        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("media_creation/session/record/out_0.png") != null)
            assertTrue(zip.getEntry("media_creation/session/draft/asset.jpg") != null)
            assertEquals(null, zip.getEntry("media_creation/session/record/out_1.mp4.part"))
        }
        deleteWithoutFollowingLinks(session)
        manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        assertFalse(session.exists())
        // Applying the staged settings would write to the real settings store of the app under test.
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("image", File(session, "record/out_0.png").readText())
        assertEquals("asset", File(session, "draft/asset.jpg").readText())
        assertFalse(File(session, "record/out_1.mp4.part").exists())
    }

    @Test fun unsupportedSchemaIsRejectedBeforePublishing() = runBlocking {
        val future = File(directory, "future")
        withRoom(future) {
            it.openHelper.writableDatabase.version = 9999
        }
        val archive = File(directory, "future.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, future) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    private fun deleteWithoutFollowingLinks(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        // walkFileTree 默认不启用 FOLLOW_LINKS，悬空链接及目录链接都只删除链接自身。
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun <T> withRoom(file: File, block: (AppDatabase) -> T): T {
        val room = AppDatabaseFactory.create(context, file.path)
        return try {
            block(room)
        } finally {
            room.close()
        }
    }
}
