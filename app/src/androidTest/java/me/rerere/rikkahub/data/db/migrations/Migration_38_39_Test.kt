package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_38_39_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun empty38MigratesToNullableBlobAndNonNullProfileAndDimensionsWithDefaults() {
        val name = "migration-38-39-schema-test"
        helper.createDatabase(name, 38).close()

        helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).use { db ->
            db.query("PRAGMA table_info(`memory_chunk`)").use { cursor ->
                val columns = buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            Triple(
                                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                                cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                            ),
                        )
                    }
                }
                assertEquals(10, columns.size)
                assertEquals(Triple("BLOB", 0, null), columns["embedding"])
                assertEquals(Triple("TEXT", 1, "''"), columns["embedding_profile"])
                assertEquals(Triple("INTEGER", 1, "0"), columns["embedding_dimensions"])
            }
        }
    }

    @Test
    fun allExistingEmbeddingColumnsPreserveValuesAndMigrationCanRunRepeatedly() {
        assertExistingEmbeddingColumnsSurvive(
            "migration-38-39-existing-all-embedding-columns-test",
            setOf("embedding", "embedding_profile", "embedding_dimensions"),
        )
    }

    @Test
    fun everyPartialEmbeddingColumnCombinationPreservesValuesAndAddsMissingColumns() {
        listOf(
            setOf("embedding"),
            setOf("embedding_profile"),
            setOf("embedding_dimensions"),
            setOf("embedding", "embedding_profile"),
            setOf("embedding", "embedding_dimensions"),
            setOf("embedding_profile", "embedding_dimensions"),
        ).forEachIndexed { index, columns ->
            assertExistingEmbeddingColumnsSurvive("migration-38-39-partial-embedding-$index-test", columns)
        }
    }

    @Test
    fun existingEmbeddingColumnWithWrongTypeIsRejectedByRoomValidation() {
        val name = "migration-38-39-wrong-embedding-type-test"
        helper.createDatabase(name, 38).use { db ->
            db.execSQL("ALTER TABLE `memory_chunk` ADD COLUMN `embedding` TEXT")
            Migration_38_39.migrate(db)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).close()
        }
        assertTrue(error.message.orEmpty().replace(Regex("\\s+"), " ").contains("Migration didn't properly handle: memory_chunk"))
        assertTrue(error.message.orEmpty().contains("embedding"))
    }

    @Test
    fun existingEmbeddingProfileWithWrongDefaultIsRejectedByRoomValidation() {
        val name = "migration-38-39-wrong-profile-default-test"
        helper.createDatabase(name, 38).use { db ->
            db.execSQL("ALTER TABLE `memory_chunk` ADD COLUMN `embedding_profile` TEXT NOT NULL DEFAULT 'legacy'")
            Migration_38_39.migrate(db)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).close()
        }
        assertTrue(error.message.orEmpty().replace(Regex("\\s+"), " ").contains("Migration didn't properly handle: memory_chunk"))
        assertTrue(error.message.orEmpty().contains("embedding_profile"))
    }

    private fun assertExistingEmbeddingColumnsSurvive(name: String, columns: Set<String>) {
        val embedding = byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64)
        helper.createDatabase(name, 38).use { db ->
            val definitions = mapOf(
                "embedding" to "BLOB",
                "embedding_profile" to "TEXT NOT NULL DEFAULT ''",
                "embedding_dimensions" to "INTEGER NOT NULL DEFAULT 0",
            )
            columns.forEach { column ->
                db.execSQL("ALTER TABLE `memory_chunk` ADD COLUMN `$column` ${definitions.getValue(column)}")
            }
            db.execSQL("INSERT INTO memory_file VALUES ('assistant-a', 'notes/shared.md', '原始正文', 42)")
            db.execSQL(
                "INSERT INTO memory_metadata " +
                    "(assistant_id, path, scope_kind, category, lifecycle, content_hash, revision, updated_at) " +
                    "VALUES ('assistant-a', 'notes/shared.md', 'ASSISTANT', 'UNKNOWN', 'ACTIVE', '完整hash', 'revision-old', 42)"
            )
            db.execSQL(
                "INSERT INTO memory_chunk " +
                    "(assistant_id, path, chunk_index, memory_revision, content_hash, chunker_version, content) " +
                    "VALUES ('assistant-a', 'notes/shared.md', 0, 'revision-old', '完整hash', 1, '旧分块正文')"
            )
            val values = mapOf("embedding" to embedding, "embedding_profile" to "provider:model", "embedding_dimensions" to 2)
            columns.forEach { column ->
                db.execSQL("UPDATE memory_chunk SET `$column` = ?", arrayOf(values.getValue(column)))
            }
            Migration_38_39.migrate(db)
            Migration_38_39.migrate(db)
        }

        helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).use { db ->
            db.query("SELECT content, embedding, embedding_profile, embedding_dimensions FROM memory_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧分块正文", cursor.getString(0))
                if ("embedding" in columns) {
                    assertArrayEquals(embedding, cursor.getBlob(1))
                } else {
                    assertNull(cursor.getBlob(1))
                }
                assertEquals(if ("embedding_profile" in columns) "provider:model" else "", cursor.getString(2))
                assertEquals(if ("embedding_dimensions" in columns) 2 else 0, cursor.getInt(3))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun oldChunksBodyMetadataSourcesAndJobsSurviveAndNewVectorsRoundTrip() {
        val name = "migration-38-39-data-test"
        helper.createDatabase(name, 38).apply {
            execSQL("INSERT INTO memory_file VALUES ('assistant-a', 'notes/shared.md', '原始正文', 42)")
            execSQL(
                "INSERT INTO memory_metadata " +
                    "(assistant_id, path, scope_kind, category, lifecycle, content_hash, revision, updated_at) " +
                    "VALUES ('assistant-a', 'notes/shared.md', 'ASSISTANT', 'UNKNOWN', 'ACTIVE', '完整hash', 'revision-old', 42)"
            )
            execSQL(
                "INSERT INTO memory_source " +
                    "(assistant_id, path, source_id, kind, status, memory_revision, source_json) " +
                    "VALUES ('assistant-a', 'notes/shared.md', 'source-1', 'MANUAL', 'CURRENT', 'revision-old', '{}')"
            )
            execSQL(
                "INSERT INTO memory_chunk " +
                    "(assistant_id, path, chunk_index, memory_revision, content_hash, chunker_version, content) " +
                    "VALUES ('assistant-a', 'notes/shared.md', 0, 'revision-old', '完整hash', 1, '旧分块正文')"
            )
            execSQL(
                "INSERT INTO memory_index_job VALUES " +
                    "('assistant-a', 'notes/shared.md', 'revision-old', '完整hash', 42)"
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 39, true, Migration_38_39).use { db ->
            db.query("SELECT * FROM memory_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("assistant-a", cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
                assertEquals("notes/shared.md", cursor.getString(cursor.getColumnIndexOrThrow("path")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("chunk_index")))
                assertEquals("revision-old", cursor.getString(cursor.getColumnIndexOrThrow("memory_revision")))
                assertEquals("完整hash", cursor.getString(cursor.getColumnIndexOrThrow("content_hash")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("chunker_version")))
                assertEquals("旧分块正文", cursor.getString(cursor.getColumnIndexOrThrow("content")))
                assertNull(cursor.getBlob(cursor.getColumnIndexOrThrow("embedding")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("embedding_profile")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("embedding_dimensions")))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT f.content, f.updated_at, m.revision, m.content_hash, s.source_id, s.source_json, j.queued_at " +
                    "FROM memory_file f JOIN memory_metadata m USING (assistant_id, path) " +
                    "JOIN memory_source s USING (assistant_id, path) JOIN memory_index_job j USING (assistant_id, path)"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("原始正文", cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
                assertEquals("revision-old", cursor.getString(2))
                assertEquals("完整hash", cursor.getString(3))
                assertEquals("source-1", cursor.getString(4))
                assertEquals("{}", cursor.getString(5))
                assertEquals(42L, cursor.getLong(6))
                assertFalse(cursor.moveToNext())
            }
            val embedding = byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64)
            db.execSQL(
                "UPDATE memory_chunk SET embedding = ?, embedding_profile = ?, embedding_dimensions = ?",
                arrayOf<Any>(embedding, "provider:model", 2),
            )
            db.query("SELECT embedding, embedding_profile, embedding_dimensions FROM memory_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertArrayEquals(embedding, cursor.getBlob(0))
                assertEquals("provider:model", cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
            }
            db.setForeignKeyConstraintsEnabled(true)
            db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
            db.execSQL("DELETE FROM memory_file WHERE assistant_id = 'assistant-a' AND path = 'notes/shared.md'")
            listOf("memory_metadata", "memory_source", "memory_chunk", "memory_index_job").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }
}
