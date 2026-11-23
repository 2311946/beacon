package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Locale
import java.util.UUID

/** 只修复恢复暂存副本中的已知分支差异；事务及最终完整 schema 校验由调用方负责。 */
internal object RestoreSchemaCompatibility {
    fun repair(db: SupportSQLiteDatabase) {
        // These are shared source tables, not optional Beacon features. Never turn a
        // damaged/unknown backup into an apparently valid empty chat database.
        for (table in listOf("ConversationEntity", "message_node", "workspaces", "memory_file")) {
            check(tableExists(db, table)) { "备份缺少核心表 $table，拒绝生成空表替代原数据" }
        }
        db.addColumnIfMissing("workspaces", "storage_mounts", "TEXT NOT NULL DEFAULT '[]'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `autonomous_activity` (`id` TEXT NOT NULL, " +
                "`assistant_id` TEXT NOT NULL, `assistant_name` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `content` TEXT NOT NULL, `decision` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_autonomous_activity_assistant_id_created_at` " +
                "ON `autonomous_activity` (`assistant_id`, `created_at`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `assistant_calendar_day` (`assistant_id` TEXT NOT NULL, " +
                "`date` TEXT NOT NULL, `entries_json` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                "PRIMARY KEY(`assistant_id`, `date`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                "ON `message_node` (`conversation_id`, `node_index`)"
        )
        db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")

        // 只有整张用量表缺失时才沿用历史回填，已有流水（即便为空）绝不重复生成。
        if (!tableExists(db, "usage_record")) {
            Migration_30_31.migrate(db)
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
        repairMemory(db)
        repairConsolidation(db)
    }

    private fun repairMemory(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_metadata` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`scope_kind` TEXT NOT NULL, `category` TEXT NOT NULL, `lifecycle` TEXT NOT NULL, " +
                "`content_hash` TEXT NOT NULL, `revision` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`assistant_id`, `path`), FOREIGN KEY(`assistant_id`, `path`) " +
                "REFERENCES `memory_file`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_source` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`source_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`memory_revision` TEXT NOT NULL, `conversation_id` TEXT, `message_id` TEXT, `source_json` TEXT NOT NULL, " +
                "PRIMARY KEY(`assistant_id`, `path`, `source_id`), FOREIGN KEY(`assistant_id`, `path`) " +
                "REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_source_assistant_id_conversation_id_message_id` " +
                "ON `memory_source` (`assistant_id`, `conversation_id`, `message_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_chunk` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`chunk_index` INTEGER NOT NULL, `memory_revision` TEXT NOT NULL, `content_hash` TEXT NOT NULL, " +
                "`chunker_version` INTEGER NOT NULL, `content` TEXT NOT NULL, `embedding` BLOB, " +
                "`embedding_profile` TEXT NOT NULL DEFAULT '', `embedding_dimensions` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`assistant_id`, `path`, `chunk_index`), FOREIGN KEY(`assistant_id`, `path`) " +
                "REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.addColumnIfMissing("memory_chunk", "embedding", "BLOB")
        db.addColumnIfMissing("memory_chunk", "embedding_profile", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("memory_chunk", "embedding_dimensions", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_index_job` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`memory_revision` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `queued_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`assistant_id`, `path`), FOREIGN KEY(`assistant_id`, `path`) " +
                "REFERENCES `memory_metadata`(`assistant_id`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )

        val unknownSource = JsonInstant.encodeToString(MemorySource(kind = MemorySourceKind.UNKNOWN))
        // 不重建 memory_file，也不覆盖现有 metadata 的分类、revision、来源或索引任务。
        db.query(
            "SELECT f.`assistant_id`, f.`path`, f.`content`, f.`updated_at` FROM `memory_file` AS f " +
                "LEFT JOIN `memory_metadata` AS m ON m.`assistant_id` = f.`assistant_id` AND m.`path` = f.`path` " +
                "WHERE m.`assistant_id` IS NULL"
        ).use { cursor ->
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
                    arrayOf<Any>(namespace, path, scopeKind, hash, revision, updatedAt)
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
                    arrayOf<Any>(namespace, path, revision, hash, updatedAt)
                )
            }
        }
    }

    private fun repairConsolidation(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "memory_consolidation")) {
            createConsolidation(db, "memory_consolidation")
            return
        }
        val columns = db.query("PRAGMA table_info(`memory_consolidation`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            buildList {
                while (cursor.moveToNext()) {
                    add(LegacyColumn(
                        name = cursor.getString(nameIndex).lowercase(Locale.ROOT),
                        type = cursor.getString(typeIndex).uppercase(Locale.ROOT),
                        notNull = cursor.getInt(notNullIndex),
                        defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                        primaryKeyPosition = cursor.getInt(pkIndex),
                    ))
                }
            }
        }
        // 新式复合主键表完全保留，由 Room 检查其类型、PK、外键和未知列。
        if (columns.any { it.name == "assistant_id" }) return
        check(columns.toSet() == setOf(
            LegacyColumn("conversation_id", "TEXT", 1, null, 1),
            LegacyColumn("consolidated_at", "INTEGER", 1, null, 0),
        ) && columns.size == 2) {
            "备份中的 memory_consolidation 不是已知的历史两列表，拒绝重建以免丢失数据"
        }
        // table_info 不包含生成列；它们同样不能在重建中被静默丢弃。
        db.query("PRAGMA table_xinfo(`memory_consolidation`)").use { cursor ->
            check(cursor.count == 2) { "备份中的 memory_consolidation 含有未知隐藏列，拒绝重建" }
        }
        db.query(
            "SELECT 1 FROM `memory_consolidation` AS m LEFT JOIN `ConversationEntity` AS c " +
                "ON c.`id` = m.`conversation_id` WHERE c.`id` IS NULL OR c.`assistant_id` IS NULL LIMIT 1"
        ).use { cursor ->
            check(!cursor.moveToFirst()) { "备份中的历史 memory_consolidation 存在找不到 owner 的行，拒绝丢弃进度" }
        }
        createConsolidation(db, "restore_memory_consolidation_new")
        db.execSQL(
            "INSERT INTO `restore_memory_consolidation_new` (`conversation_id`, `consolidated_at`, `assistant_id`) " +
                "SELECT m.`conversation_id`, m.`consolidated_at`, c.`assistant_id` " +
                "FROM `memory_consolidation` AS m JOIN `ConversationEntity` AS c ON c.`id` = m.`conversation_id`"
        )
        db.query(
            "SELECT (SELECT COUNT(*) FROM `memory_consolidation`) = " +
                "(SELECT COUNT(*) FROM `restore_memory_consolidation_new`)"
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "恢复历史整理进度时行数发生变化" }
        }
        db.execSQL("DROP TABLE `memory_consolidation`")
        db.execSQL("ALTER TABLE `restore_memory_consolidation_new` RENAME TO `memory_consolidation`")
    }

    private fun createConsolidation(db: SupportSQLiteDatabase, table: String) {
        // 不使用 IF NOT EXISTS，避免覆盖备份中恰好同名的未知暂存表。
        db.execSQL(
            "CREATE TABLE `$table` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, " +
                "`assistant_id` TEXT NOT NULL, PRIMARY KEY(`conversation_id`, `assistant_id`), " +
                "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private data class LegacyColumn(
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )
}
