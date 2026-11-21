package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(36, 37)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memory_metadata` (
                    `assistant_id` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `scope_kind` TEXT NOT NULL,
                    `category` TEXT NOT NULL,
                    `lifecycle` TEXT NOT NULL,
                    `content_hash` TEXT NOT NULL,
                    `revision` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`assistant_id`, `path`),
                    FOREIGN KEY(`assistant_id`, `path`) REFERENCES `memory_file`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memory_source` (
                    `assistant_id` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `source_id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `memory_revision` TEXT NOT NULL,
                    `conversation_id` TEXT,
                    `message_id` TEXT,
                    `source_json` TEXT NOT NULL,
                    PRIMARY KEY(`assistant_id`, `path`, `source_id`),
                    FOREIGN KEY(`assistant_id`, `path`) REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_source_assistant_id_conversation_id_message_id` " +
                    "ON `memory_source` (`assistant_id`, `conversation_id`, `message_id`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memory_chunk` (
                    `assistant_id` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `chunk_index` INTEGER NOT NULL,
                    `memory_revision` TEXT NOT NULL,
                    `content_hash` TEXT NOT NULL,
                    `chunker_version` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    PRIMARY KEY(`assistant_id`, `path`, `chunk_index`),
                    FOREIGN KEY(`assistant_id`, `path`) REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memory_index_job` (
                    `assistant_id` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `memory_revision` TEXT NOT NULL,
                    `content_hash` TEXT NOT NULL,
                    `queued_at` INTEGER NOT NULL,
                    PRIMARY KEY(`assistant_id`, `path`),
                    FOREIGN KEY(`assistant_id`, `path`) REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            val unknownSource = JsonInstant.encodeToString(MemorySource(kind = MemorySourceKind.UNKNOWN))
            db.query("SELECT `assistant_id`, `path`, `content`, `updated_at` FROM `memory_file`").use { cursor ->
                while (cursor.moveToNext()) {
                    val namespace = cursor.getString(0)
                    val path = cursor.getString(1)
                    val hash = memoryContentHash(cursor.getString(2))
                    val updatedAt = cursor.getLong(3)
                    val revision = UUID.randomUUID().toString()
                    val scopeKind = if (namespace == MemoryScope.GLOBAL_NAMESPACE) "EXPLICIT_GLOBAL" else "ASSISTANT"
                    db.execSQL(
                        "INSERT INTO `memory_metadata` " +
                            "(`assistant_id`, `path`, `scope_kind`, `category`, `lifecycle`, `content_hash`, `revision`, `updated_at`) " +
                            "VALUES (?, ?, ?, 'UNKNOWN', 'ACTIVE', ?, ?, ?)",
                        arrayOf(namespace, path, scopeKind, hash, revision, updatedAt)
                    )
                    db.execSQL(
                        "INSERT INTO `memory_source` " +
                            "(`assistant_id`, `path`, `source_id`, `kind`, `status`, `memory_revision`, `conversation_id`, `message_id`, `source_json`) " +
                            "VALUES (?, ?, ?, 'UNKNOWN', 'CURRENT', ?, NULL, NULL, ?)",
                        arrayOf(namespace, path, UUID.randomUUID().toString(), revision, unknownSource)
                    )
                    db.execSQL(
                        "INSERT INTO `memory_index_job` " +
                            "(`assistant_id`, `path`, `memory_revision`, `content_hash`, `queued_at`) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(namespace, path, revision, hash, updatedAt)
                    )
                }
            }
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
