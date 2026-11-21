package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_37_38_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun empty37MigratesToCompositePrimaryKeyAndConversationForeignKey() {
        val name = "migration-37-38-schema-test"
        helper.createDatabase(name, 37).close()

        helper.runMigrationsAndValidate(name, 38, true, Migration_37_38).use { db ->
            db.query("PRAGMA table_info(`memory_consolidation`)").use { cursor ->
                val columns = mutableMapOf<String, Pair<String, Int>>()
                while (cursor.moveToNext()) {
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    columns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                        cursor.getString(cursor.getColumnIndexOrThrow("type")) to
                            cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                }
                assertEquals(
                    mapOf(
                        "conversation_id" to ("TEXT" to 1),
                        "assistant_id" to ("TEXT" to 2),
                        "consolidated_at" to ("INTEGER" to 0),
                    ),
                    columns,
                )
            }
            db.query("PRAGMA foreign_key_list(`memory_consolidation`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ConversationEntity", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("conversation_id", cursor.getString(cursor.getColumnIndexOrThrow("from")))
                assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
                assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun legacyProgressBindsOnlyToOwnerIncludingGroupsWithoutParsingConfig() {
        val name = "migration-37-38-owner-test"
        val configs = listOf("", "{\"isGroupChat\":true}", "旧版未解析配置")
        helper.createDatabase(name, 37).apply {
            configs.forEachIndexed { index, config ->
                insertConversation(this, "conversation-$index", "owner-$index", 500L, config)
                execSQL(
                    "INSERT INTO memory_consolidation (conversation_id, consolidated_at) VALUES (?, ?)",
                    arrayOf<Any>("conversation-$index", 100L + index),
                )
            }
            insertConversation(this, "without-progress", "owner", 500L)
            execSQL(
                "INSERT INTO memory_file (assistant_id, path, content, updated_at) " +
                    "VALUES ('owner', 'notes/legacy.md', '保持原样', 42)"
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 38, true, Migration_37_38).use { db ->
            db.query(
                "SELECT m.conversation_id, m.assistant_id, m.consolidated_at, c.config " +
                    "FROM memory_consolidation m JOIN conversationentity c ON c.id = m.conversation_id " +
                    "ORDER BY m.conversation_id"
            ).use { cursor ->
                configs.forEachIndexed { index, config ->
                    assertTrue(cursor.moveToNext())
                    assertEquals("conversation-$index", cursor.getString(0))
                    assertEquals("owner-$index", cursor.getString(1))
                    assertEquals(100L + index, cursor.getLong(2))
                    assertEquals(config, cursor.getString(3))
                }
                assertFalse(cursor.moveToNext())
            }
            db.execSQL(
                "INSERT INTO memory_consolidation (conversation_id, consolidated_at, assistant_id) " +
                    "VALUES ('conversation-1', 7, 'second-member')"
            )
            db.query(
                "SELECT assistant_id, consolidated_at FROM memory_consolidation " +
                    "WHERE conversation_id = 'conversation-1' ORDER BY assistant_id"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("owner-1", cursor.getString(0))
                assertEquals(101L, cursor.getLong(1))
                assertTrue(cursor.moveToNext())
                assertEquals("second-member", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT content, updated_at FROM memory_file").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("保持原样", cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun migratedForeignKeyCascadesAllMembersAndRejectsMissingConversations() {
        val name = "migration-37-38-foreign-key-test"
        helper.createDatabase(name, 37).apply {
            insertConversation(this, "group", "owner", 500L, "{\"isGroupChat\":true}")
            insertConversation(this, "unrelated", "other", 500L)
            execSQL("INSERT INTO memory_consolidation VALUES ('group', 100)")
            execSQL("INSERT INTO memory_consolidation VALUES ('unrelated', 200)")
            close()
        }

        helper.runMigrationsAndValidate(name, 38, true, Migration_37_38).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL(
                "INSERT INTO memory_consolidation (conversation_id, consolidated_at, assistant_id) " +
                    "VALUES ('group', 300, 'second-member')"
            )
            assertTrue(runCatching {
                db.execSQL(
                    "INSERT INTO memory_consolidation (conversation_id, consolidated_at, assistant_id) " +
                        "VALUES ('missing', 400, 'owner')"
                )
            }.isFailure)
            db.execSQL("DELETE FROM conversationentity WHERE id = 'group'")
            db.query("SELECT conversation_id, assistant_id, consolidated_at FROM memory_consolidation").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("unrelated", cursor.getString(0))
                assertEquals("other", cursor.getString(1))
                assertEquals(200L, cursor.getLong(2))
                assertFalse(cursor.moveToNext())
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun nonFirstGroupMemberHasIndependentProgressAndUpsertDoesNotOverwriteOwner() = runBlocking {
        val database = inMemoryDatabase()
        try {
            insertConversation(database.openHelper.writableDatabase, "group", "owner", 500L)
            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("group", 500L, "owner"))
            assertNull(dao.getConsolidatedAt("group", "second-member"))
            assertEquals(
                listOf("group"),
                dao.getUpdatedConversationIdsForAssistant("second-member", listOf("group"), 0L, 10),
            )
            dao.upsert(MemoryConsolidationEntity("group", 100L, "second-member"))
            dao.upsert(MemoryConsolidationEntity("group", 600L, "second-member"))
            assertEquals(500L, requireNotNull(dao.getConsolidatedAt("group", "owner")))
            assertEquals(600L, requireNotNull(dao.getConsolidatedAt("group", "second-member")))
            assertTrue(
                dao.getUpdatedConversationIdsForAssistant("second-member", listOf("group"), 0L, 10).isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun candidatesIncludeOnlyOwnConversationsAndExplicitGroupsUsingRequestedAssistantProgress() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val db = database.openHelper.writableDatabase
            insertConversation(db, "own", "target", 300L)
            insertConversation(db, "group", "owner", 400L, "{\"isGroupChat\":true}")
            insertConversation(db, "owner-private", "owner", 500L)
            insertConversation(db, "unlisted-group", "owner", 600L, "{\"isGroupChat\":true}")
            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("own", 900L, "other-member"))
            dao.upsert(MemoryConsolidationEntity("group", 900L, "owner"))
            assertEquals(
                listOf("group", "own"),
                dao.getUpdatedConversationIdsForAssistant("target", listOf("group"), 0L, 10),
            )
            dao.upsert(MemoryConsolidationEntity("group", 400L, "target"))
            assertEquals(
                listOf("own"),
                dao.getUpdatedConversationIdsForAssistant("target", listOf("group"), 0L, 10),
            )
            assertTrue(dao.getUpdatedConversationIdsForAssistant("unrelated", emptyList(), 0L, 10).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun candidateQueryHonorsSinceLimitOrderAndEmptyGroupList() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val db = database.openHelper.writableDatabase
            insertConversation(db, "old", "target", 99L)
            insertConversation(db, "at-since", "target", 100L)
            insertConversation(db, "newest", "target", 300L)
            insertConversation(db, "done", "target", 400L)
            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("done", 400L, "target"))
            assertEquals(
                listOf("newest", "at-since"),
                dao.getUpdatedConversationIdsForAssistant("target", emptyList(), 100L, 10),
            )
            assertEquals(
                listOf("newest"),
                dao.getUpdatedConversationIdsForAssistant("target", emptyList(), 100L, 1),
            )
            assertTrue(dao.getUpdatedConversationIdsForAssistant("target", emptyList(), 100L, 0).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun candidatePagesPassFiftyOwnerGroupsWithStableTiesAndIndependentMemberProgress() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val db = database.openHelper.writableDatabase
            val ownerGroups = (0 until 60).map { "owner-group-${it.toString().padStart(3, '0')}" }
            ownerGroups.forEach { id ->
                insertConversation(db, id, "target", 500L, "{\"isGroupChat\":true}")
            }
            insertConversation(db, "ready-private", "target", 100L)
            insertConversation(db, "ready-group", "owner", 200L, "{\"isGroupChat\":true}")
            insertConversation(db, "foreign-private", "owner", 900L)
            insertConversation(db, "already-done", "target", 600L)
            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("ready-group", 900L, "owner"))
            dao.upsert(MemoryConsolidationEntity("already-done", 600L, "target"))
            val expected = ownerGroups.reversed() + listOf("ready-group", "ready-private")

            val first = dao.getUpdatedConversationIdsForAssistant("target", listOf("ready-group"), 100L, 50, 0)
            val second = dao.getUpdatedConversationIdsForAssistant("target", listOf("ready-group"), 100L, 50, 50)

            assertEquals(expected.take(50), first)
            assertEquals(expected.drop(50), second)
            assertEquals(expected, (first + second).distinct())
            assertTrue(
                dao.getUpdatedConversationIdsForAssistant("target", listOf("ready-group"), 100L, 50, 100).isEmpty()
            )
            assertNull(dao.getConsolidatedAt("ready-group", "target"))
            dao.upsert(MemoryConsolidationEntity("ready-group", 200L, "target"))
            assertEquals(900L, requireNotNull(dao.getConsolidatedAt("ready-group", "owner")))
            assertEquals(
                ownerGroups.reversed().drop(50) + "ready-private",
                dao.getUpdatedConversationIdsForAssistant("target", listOf("ready-group"), 100L, 50, 50),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyCandidateQueriesJoinOnlyOwnerProgress() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val db = database.openHelper.writableDatabase
            insertConversation(db, "pending", "owner", 200L)
            insertConversation(db, "done", "owner", 300L)
            insertConversation(db, "outside", "outside-owner", 400L)
            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("pending", 900L, "member"))
            dao.upsert(MemoryConsolidationEntity("done", 300L, "owner"))
            dao.upsert(MemoryConsolidationEntity("done", 0L, "member"))
            assertEquals(listOf("pending"), dao.getUpdatedConversationIds(listOf("owner"), 0L, 10))
            assertEquals(listOf("owner"), dao.getAssistantIdsWithUpdatedConversations(listOf("owner"), 0L))
            dao.upsert(MemoryConsolidationEntity("pending", 200L, "owner"))
            assertTrue(dao.getUpdatedConversationIds(listOf("owner"), 0L, 10).isEmpty())
            assertTrue(dao.getAssistantIdsWithUpdatedConversations(listOf("owner"), 0L).isEmpty())
        } finally {
            database.close()
        }
    }

    private fun inMemoryDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()

    private fun insertConversation(
        db: SupportSQLiteDatabase,
        id: String,
        assistantId: String,
        updatedAt: Long,
        config: String = "",
    ) {
        db.execSQL(
            "INSERT INTO conversationentity (id, assistant_id, title, nodes, create_at, update_at, config) " +
                "VALUES (?, ?, '测试会话', '[]', 1, ?, ?)",
            arrayOf<Any>(id, assistantId, updatedAt, config),
        )
    }
}
