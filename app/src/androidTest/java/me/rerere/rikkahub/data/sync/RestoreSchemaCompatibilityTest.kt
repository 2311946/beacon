package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
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
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 以下都是基于导出 schema 构造的版本碰撞 fixture，不代表实际取得的 Rikka 备份。 */
@RunWith(AndroidJUnit4::class)
class RestoreSchemaCompatibilityTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager
    private val sharedPreferencesNames = mutableSetOf<String>()
    private val messages = JsonInstant.encodeToString(
        listOf(UIMessage.assistant("版本碰撞中的聊天正文").copy(
            usage = TokenUsage(promptTokens = 17, completionTokens = 9, cachedTokens = 3),
        )),
    )
    private val memoryBody = "原始 memory_file 正文\n不能用空表重建替代"
    private val mounts = """[{"id":"shared","path":"/mnt/shared"}]"""

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "restore-schema-test-").toFile()
        val preferencesPrefix = "restore-schema-test-${UUID.randomUUID()}-"
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
        manager = newManager()
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
            // fixture 不含符号链接，只清理本测试创建的隔离目录。
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun constructedSchema32CollisionRestoresThroughZip() = runBlocking { restoreCollision(32) }

    @Test fun constructedSchema33CollisionRestoresThroughZip() = runBlocking { restoreCollision(33) }

    @Test fun constructedSchema34CollisionRestoresThroughZip() = runBlocking { restoreCollision(34) }

    @Test fun constructedSchema35CollisionPreservesExistingStorageMounts() = runBlocking { restoreCollision(35) }

    @Test fun constructedSchema36CollisionRestoresThroughZip() = runBlocking { restoreCollision(36) }

    @Test fun constructedSchema37CollisionPreservesLegacyConsolidation() = runBlocking { restoreCollision(37) }

    @Test fun constructedSchema38CollisionPreservesBothConsolidationMembers() = runBlocking { restoreCollision(38) }

    @Test fun constructedSchema39WithUnchangedIdentityStillRepairsMissingTables() = runBlocking {
        restoreCollision(39)
    }

    @Test fun constructedSchema39WithLegacyConsolidationKeepsOwnerProgress() = runBlocking {
        val fixture = createCollisionFixture(39, legacyConsolidation = true)
        stageAndApply(fixture.archive)
        assertRestoredData(legacyConsolidation = true)
    }

    @Test fun missingUsageAndMemoryMetadataAreBackfilledOnlyOnceAcrossRepeatedRestores() = runBlocking {
        val fixture = createCollisionFixture(39, dropUsage = true)
        stageAndApply(fixture.archive)
        assertRestoredData(legacyConsolidation = false)
        val originalMemoryState = memoryState(liveDatabase.openHelper.readableDatabase)
        val repairedSnapshot = File(directory, "repaired-snapshot")
        DatabaseBackup.createSnapshot(liveDatabase.openHelper.writableDatabase, repairedSnapshot)
        val repairedArchive = zipDatabase(repairedSnapshot, "repaired.zip")
        val originalArchiveBytes = fixture.archive.readBytes()
        val repairedArchiveBytes = repairedArchive.readBytes()

        repeat(2) {
            stageAndApply(repairedArchive)
            assertRestoredData(legacyConsolidation = false)
            assertEquals(originalMemoryState, memoryState(liveDatabase.openHelper.readableDatabase))
            assertArrayEquals(originalArchiveBytes, fixture.archive.readBytes())
            assertArrayEquals(repairedArchiveBytes, repairedArchive.readBytes())
            assertFalse(BackupManager.applyPendingRestore(context, JsonInstant))
        }
    }

    @Test fun wrongAutonomousColumnTypeWithMatching39IdentityIsRejectedBeforePublishing() = runBlocking {
        val fixture = createCollisionFixture(39) { db ->
            // 保留全部列、PK 和索引，只制造 created_at 的类型不兼容。
            db.execSQL(
                "CREATE TABLE autonomous_activity (id TEXT NOT NULL, assistant_id TEXT NOT NULL, " +
                    "assistant_name TEXT NOT NULL, conversation_id TEXT NOT NULL, created_at TEXT NOT NULL, " +
                    "content TEXT NOT NULL, decision TEXT NOT NULL, PRIMARY KEY(id))",
            )
            db.execSQL(
                "CREATE INDEX index_autonomous_activity_assistant_id_created_at " +
                    "ON autonomous_activity (assistant_id, created_at)",
            )
            db.execSQL(
                "INSERT INTO autonomous_activity VALUES " +
                    "('incompatible', 'owner', '保留的助手', 'conversation', '不能静默转换', '保留的内容', 'SKIP')",
            )
        }
        assertRejectedWithoutPublishing(fixture)
    }

    @Test fun schema40IsRejectedBeforePublishingWithoutChangingLiveOrArchive() = runBlocking {
        val fixture = createCollisionFixture(39) { it.version = 40 }
        assertRejectedWithoutPublishing(fixture)
    }

    private suspend fun restoreCollision(version: Int) {
        val fixture = createCollisionFixture(version)
        val sourceBytes = fixture.source.readBytes()
        val archiveBytes = fixture.archive.readBytes()
        stageAndApply(fixture.archive)
        assertRestoredData(legacyConsolidation = version < 38)
        liveDatabase.openHelper.readableDatabase.query("SELECT id, created_at FROM usage_record").use {
            assertTrue(it.moveToFirst())
            assertEquals(77L, it.getLong(0))
            assertEquals(100L, it.getLong(1))
            assertFalse(it.moveToNext())
        }
        assertEquals(version, DatabaseBackup.readUserVersion(context, fixture.source))
        assertArrayEquals(sourceBytes, fixture.source.readBytes())
        assertArrayEquals(archiveBytes, fixture.archive.readBytes())
    }

    private suspend fun stageAndApply(archive: File) {
        val before = probe(liveDatabase)
        assertFalse(manager.hasPendingRestore())
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals(before, probe(liveDatabase))
        assertTrue(manager.hasPendingRestore())
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertEquals(39, DatabaseBackup.readUserVersion(context, staged))
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        // 归档仅含数据库，不读取或覆盖被测应用的全局 SettingsStore。
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").exists())
        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertFalse(manager.hasPendingRestore())
        liveDatabase = AppDatabaseFactory.create(context)
        manager = newManager()
        assertEquals("archived", probe(liveDatabase))
    }

    private suspend fun assertRejectedWithoutPublishing(fixture: CollisionFixture) {
        val sourceBytes = fixture.source.readBytes()
        val archiveBytes = fixture.archive.readBytes()
        val liveFile = context.getDatabasePath("rikka_hub")
        DatabaseBackup.checkpoint(liveDatabase.openHelper.writableDatabase)
        val liveBytes = liveFile.readBytes()
        val failure = runCatching {
            manager.stageRestore(fixture.archive, includeDatabase = true, includeFiles = false)
        }.exceptionOrNull()
        assertTrue("不兼容 fixture 必须在 stageRestore 内失败，实际异常：$failure", failure is Exception)
        assertFalse(manager.hasPendingRestore())
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertFalse(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("live", probe(liveDatabase))
        assertArrayEquals(liveBytes, liveFile.readBytes())
        assertArrayEquals(sourceBytes, fixture.source.readBytes())
        assertArrayEquals(archiveBytes, fixture.archive.readBytes())
    }

    private fun createCollisionFixture(
        version: Int,
        dropUsage: Boolean = false,
        legacyConsolidation: Boolean = version < 38,
        modify: (SupportSQLiteDatabase) -> Unit = {},
    ): CollisionFixture {
        val source = File(directory, "constructed-$version-${UUID.randomUUID()}")
        migrationHelper.createDatabase(source.absolutePath, version).use { db ->
            val identityBefore = identity(db)
            // 先删依赖表再删 metadata；从不删除 memory_file 正文。
            listOf(
                "memory_source", "memory_chunk", "memory_index_job", "memory_metadata",
                "autonomous_activity", "assistant_calendar_day",
            ).forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
            if (dropUsage) db.execSQL("DROP TABLE usage_record")
            if (!hasColumn(db, "workspaces", "storage_mounts")) {
                // 独立 DDL 模拟 fork 提前落地的列，不调用待测 repair 或迁移来构造 fixture。
                db.execSQL("ALTER TABLE workspaces ADD COLUMN storage_mounts TEXT NOT NULL DEFAULT '[]'")
            }
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, config) " +
                    "VALUES ('conversation', 'owner', '版本碰撞聊天探针', '[]', 100, 200, '')",
            )
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('node', 'conversation', 0, ?, 0)",
                arrayOf<Any>(messages),
            )
            db.execSQL(
                "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('owner', 'notes/old.md', ?, 42)",
                arrayOf<Any>(memoryBody),
            )
            db.execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, storage_mounts) " +
                    "VALUES ('workspace', '保留的工作区', 'archived-root', 'READY', 100, 200, ?)",
                arrayOf<Any>(mounts),
            )
            if (legacyConsolidation && version >= 38) {
                db.execSQL("DROP TABLE memory_consolidation")
                db.execSQL(
                    "CREATE TABLE memory_consolidation (conversation_id TEXT NOT NULL, consolidated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(conversation_id), FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
            if (legacyConsolidation) {
                db.execSQL("INSERT INTO memory_consolidation (conversation_id, consolidated_at) VALUES ('conversation', 123)")
            } else {
                db.execSQL(
                    "INSERT INTO memory_consolidation (conversation_id, consolidated_at, assistant_id) " +
                        "VALUES ('conversation', 123, 'owner'), ('conversation', 456, 'participant')",
                )
            }
            if (!dropUsage) {
                db.execSQL(
                    "INSERT INTO usage_record (id, created_at, source, assistant_id, conversation_id, " +
                        "prompt_tokens, completion_tokens, cached_tokens) " +
                        "VALUES (77, 100, 'CHAT', 'owner', 'conversation', 17, 9, 3)",
                )
            }
            modify(db)
            // 尤其是版本39，缺表后仍保留原 identity，不能借删 identity 偶然触发校验。
            assertEquals(identityBefore, identity(db))
            assertFalse(identityBefore.isBlank())
            DatabaseBackup.checkpoint(db)
        }
        DatabaseBackup.removeSidecars(source)
        return CollisionFixture(source, zipDatabase(source, source.name + ".zip"))
    }

    private fun assertRestoredData(legacyConsolidation: Boolean) {
        val db = liveDatabase.openHelper.readableDatabase
        assertEquals(39, db.version)
        assertEquals("archived", probe(liveDatabase))
        db.query("SELECT title, assistant_id FROM ConversationEntity WHERE id = 'conversation'").use {
            assertTrue(it.moveToFirst())
            assertEquals("版本碰撞聊天探针", it.getString(0))
            assertEquals("owner", it.getString(1))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT messages, node_index, select_index FROM message_node WHERE id = 'node'").use {
            assertTrue(it.moveToFirst())
            assertEquals(messages, it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT assistant_id, path, content, updated_at FROM memory_file").use {
            assertTrue(it.moveToFirst())
            assertEquals("owner", it.getString(0))
            assertEquals("notes/old.md", it.getString(1))
            assertEquals(memoryBody, it.getString(2))
            assertEquals(42L, it.getLong(3))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT name, root, storage_mounts FROM workspaces WHERE id = 'workspace'").use {
            assertTrue(it.moveToFirst())
            assertEquals("保留的工作区", it.getString(0))
            assertEquals("archived-root", it.getString(1))
            assertEquals(mounts, it.getString(2))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT assistant_id, consolidated_at FROM memory_consolidation ORDER BY assistant_id").use {
            assertTrue(it.moveToFirst())
            assertEquals("owner", it.getString(0))
            assertEquals(123L, it.getLong(1))
            if (!legacyConsolidation) {
                assertTrue(it.moveToNext())
                assertEquals("participant", it.getString(0))
                assertEquals(456L, it.getLong(1))
            }
            assertFalse(it.moveToNext())
        }
        db.query("SELECT source, assistant_id, conversation_id, prompt_tokens, completion_tokens, cached_tokens FROM usage_record").use {
            assertTrue(it.moveToFirst())
            assertEquals("CHAT", it.getString(0))
            assertEquals("owner", it.getString(1))
            assertEquals("conversation", it.getString(2))
            assertEquals(17, it.getInt(3))
            assertEquals(9, it.getInt(4))
            assertEquals(3, it.getInt(5))
            assertFalse("usage_record 不能重复回填", it.moveToNext())
        }
        db.query("SELECT assistant_id, path, scope_kind, content_hash, revision, updated_at FROM memory_metadata").use {
            assertTrue(it.moveToFirst())
            assertEquals("owner", it.getString(0))
            assertEquals("notes/old.md", it.getString(1))
            assertEquals("ASSISTANT", it.getString(2))
            assertEquals(memoryContentHash(memoryBody), it.getString(3))
            assertFalse(it.getString(4).isBlank())
            assertEquals(42L, it.getLong(5))
            assertFalse("memory_metadata 不能重复回填", it.moveToNext())
        }
        assertEquals(1, rowCount(db, "memory_source"))
        assertEquals(1, rowCount(db, "memory_index_job"))
        assertEquals(0, rowCount(db, "memory_chunk"))
        assertEquals(0, rowCount(db, "autonomous_activity"))
        assertEquals(0, rowCount(db, "assistant_calendar_day"))
        db.query("PRAGMA table_info(memory_chunk)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(columns.containsAll(listOf("embedding", "embedding_profile", "embedding_dimensions")))
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        db.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
            assertFalse(it.moveToNext())
        }
    }

    private fun memoryState(db: SupportSQLiteDatabase) =
        listOf("memory_metadata", "memory_source", "memory_index_job").associateWith { table ->
            db.query("SELECT * FROM `$table` ORDER BY assistant_id, path").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
            }
        }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    private fun rowCount(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { check(it.moveToFirst()); it.getInt(0) }

    private fun identity(db: SupportSQLiteDatabase): String =
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            check(it.moveToFirst())
            it.getString(0)
        }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

    private fun newManager() = BackupManager(
        context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant,
    )

    private fun zipDatabase(source: File, name: String): File = File(directory, name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(DatabaseBackup.ARCHIVE_DATABASE))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private data class CollisionFixture(val source: File, val archive: File)
}
