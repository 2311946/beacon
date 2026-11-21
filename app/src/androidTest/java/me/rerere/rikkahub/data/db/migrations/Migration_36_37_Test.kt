package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration_36_37_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun legacyFilesReceiveScopedMetadataUnknownSourcesAndPendingJobs() {
        val name = "migration-36-37-backfill-test"
        val path = "notes/shared.md"
        val files = listOf(
            Triple("assistant-a", "角色甲的记忆\n含 ' 引号和扩展汉字：\uD840\uDC00", 101L),
            Triple("assistant-b", "角色乙的独立记忆", 202L),
            Triple("__global__", "用户显式共享的记忆", 303L),
            Triple("assistant-empty", "", 0L),
        )
        helper.createDatabase(name, 36).apply {
            files.forEach { (namespace, content, updatedAt) ->
                execSQL(
                    "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(namespace, path, content, updatedAt)
                )
            }
            close()
        }

        helper.runMigrationsAndValidate(name, 37, true, Migration_36_37).use { db ->
            val revisions = mutableSetOf<String>()
            val sourceIds = mutableSetOf<String>()
            files.forEach { (namespace, content, updatedAt) ->
                val expectedHash = MessageDigest.getInstance("SHA-256")
                    .digest(content.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                db.query(
                    "SELECT f.content, f.updated_at, m.scope_kind, m.category, m.lifecycle, " +
                        "m.content_hash, m.revision, m.updated_at, s.source_id, s.kind, s.status, " +
                        "s.memory_revision, s.conversation_id, s.message_id, s.source_json, " +
                        "j.memory_revision, j.content_hash, j.queued_at " +
                        "FROM memory_file f " +
                        "JOIN memory_metadata m ON m.assistant_id = f.assistant_id AND m.path = f.path " +
                        "JOIN memory_source s ON s.assistant_id = m.assistant_id AND s.path = m.path " +
                        "JOIN memory_index_job j ON j.assistant_id = m.assistant_id AND j.path = m.path " +
                        "WHERE f.assistant_id = ? AND f.path = ?",
                    arrayOf(namespace, path)
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(content, cursor.getString(0))
                    assertEquals(updatedAt, cursor.getLong(1))
                    assertEquals(if (namespace == "__global__") "EXPLICIT_GLOBAL" else "ASSISTANT", cursor.getString(2))
                    assertEquals("UNKNOWN", cursor.getString(3))
                    assertEquals("ACTIVE", cursor.getString(4))
                    assertEquals(64, cursor.getString(5).length)
                    assertEquals(expectedHash, cursor.getString(5))
                    val revision = cursor.getString(6)
                    assertEquals(revision, UUID.fromString(revision).toString())
                    assertTrue(revisions.add(revision))
                    assertEquals(updatedAt, cursor.getLong(7))
                    val sourceId = cursor.getString(8)
                    assertEquals(sourceId, UUID.fromString(sourceId).toString())
                    assertTrue(sourceIds.add(sourceId))
                    assertEquals("UNKNOWN", cursor.getString(9))
                    assertEquals("CURRENT", cursor.getString(10))
                    assertEquals(revision, cursor.getString(11))
                    assertTrue(cursor.isNull(12))
                    assertTrue(cursor.isNull(13))
                    assertEquals(
                        MemorySource(kind = MemorySourceKind.UNKNOWN),
                        JsonInstant.decodeFromString<MemorySource>(cursor.getString(14))
                    )
                    assertEquals(revision, cursor.getString(15))
                    assertEquals(expectedHash, cursor.getString(16))
                    assertEquals(updatedAt, cursor.getLong(17))
                    assertFalse(cursor.moveToNext())
                }
            }
            listOf("memory_file", "memory_metadata", "memory_source", "memory_index_job").forEach { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(files.size, cursor.getInt(0))
                }
            }
            db.query("SELECT COUNT(*) FROM memory_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun deletingOneNamespaceCascadesWithoutDeletingTheSamePathElsewhere() {
        val name = "migration-36-37-cascade-test"
        helper.createDatabase(name, 36).apply {
            execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('assistant', 'shared.md', '私有', 10)")
            execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('__global__', 'shared.md', '共享', 20)")
            close()
        }

        helper.runMigrationsAndValidate(name, 37, true, Migration_36_37).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL(
                "INSERT INTO memory_chunk (assistant_id, path, chunk_index, memory_revision, content_hash, chunker_version, content) " +
                    "SELECT m.assistant_id, m.path, 0, m.revision, m.content_hash, 1, f.content " +
                    "FROM memory_metadata m JOIN memory_file f ON f.assistant_id = m.assistant_id AND f.path = m.path"
            )
            db.execSQL("DELETE FROM memory_file WHERE assistant_id = 'assistant' AND path = 'shared.md'")
            listOf("memory_file", "memory_metadata", "memory_source", "memory_chunk", "memory_index_job").forEach { table ->
                db.query("SELECT assistant_id, path FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("__global__", cursor.getString(0))
                    assertEquals("shared.md", cursor.getString(1))
                    assertFalse(cursor.moveToNext())
                }
            }
            db.execSQL("DELETE FROM memory_metadata WHERE assistant_id = '__global__' AND path = 'shared.md'")
            listOf("memory_metadata", "memory_source", "memory_chunk", "memory_index_job").forEach { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            db.query("SELECT content, updated_at FROM memory_file").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("共享", cursor.getString(0))
                assertEquals(20L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun beacon34MigratesThroughCompositeIndexMountsAndMemoryMetadata() {
        val name = "migration-34-37-test"
        helper.createDatabase(name, 34).apply {
            execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES ('assistant', 'legacy.md', '旧记忆', 42)")
            execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) " +
                    "VALUES ('local', 'Existing', 'local-root', 'READY', 100, 200)"
            )
            execSQL(
                "INSERT INTO autonomous_activity " +
                    "(id, assistant_id, assistant_name, conversation_id, created_at, content, decision) " +
                    "VALUES ('activity', 'assistant', '角色', 'conv', 3, '阅读', 'manual')"
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 37, true, Migration_34_35, Migration_35_36, Migration_36_37).use { db ->
            db.query("SELECT content, updated_at FROM memory_file WHERE assistant_id = 'assistant' AND path = 'legacy.md'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧记忆", cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
            }
            db.query(
                "SELECT m.scope_kind, m.category, m.lifecycle, s.kind, j.memory_revision = m.revision, " +
                    "j.content_hash = m.content_hash, j.queued_at " +
                    "FROM memory_metadata m " +
                    "JOIN memory_source s ON s.assistant_id = m.assistant_id AND s.path = m.path " +
                    "JOIN memory_index_job j ON j.assistant_id = m.assistant_id AND j.path = m.path " +
                    "WHERE m.assistant_id = 'assistant' AND m.path = 'legacy.md'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ASSISTANT", cursor.getString(0))
                assertEquals("UNKNOWN", cursor.getString(1))
                assertEquals("ACTIVE", cursor.getString(2))
                assertEquals("UNKNOWN", cursor.getString(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(1, cursor.getInt(5))
                assertEquals(42L, cursor.getLong(6))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT storage_mounts, updated_at FROM workspaces WHERE id = 'local'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertEquals(200L, cursor.getLong(1))
            }
            db.query("SELECT content FROM autonomous_activity WHERE id = 'activity'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("阅读", cursor.getString(0))
            }
            db.query("PRAGMA index_list(`message_node`)").use { cursor ->
                val indexName = cursor.getColumnIndexOrThrow("name")
                val indices = mutableSetOf<String>()
                while (cursor.moveToNext()) indices.add(cursor.getString(indexName))
                assertTrue("index_message_node_conversation_id_node_index" in indices)
                assertFalse("index_message_node_conversation_id" in indices)
            }
        }
    }
}
