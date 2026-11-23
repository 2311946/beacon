package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Forked backups may already contain a column while retaining an older user_version.
 * Only skip an existing name; Room must still validate its type, nullability and default.
 * Do not catch SQL errors: missing tables, invalid SQL and incompatible schemas must fail.
 */
internal fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    val quotedTable = "`${table.replace("`", "``")}`"
    val exists = query("PRAGMA table_info($quotedTable)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) {
            // SQLite identifiers are case-insensitive, even if PRAGMA preserves their case.
            if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                found = true
                break
            }
        }
        found
    }
    if (!exists) {
        val quotedColumn = "`${column.replace("`", "``")}`"
        execSQL("ALTER TABLE $quotedTable ADD COLUMN $quotedColumn $definition")
    }
}

/** Manual equivalents of Room's add-column auto migrations, with unchanged SQL definitions. */
internal val addColumnMigrations: Array<Migration> = arrayOf(
    columnMigration(3, 4) {
        addColumnIfMissing("ConversationEntity", "usage", "TEXT DEFAULT NULL")
    },
    columnMigration(4, 5) {
        addColumnIfMissing("ConversationEntity", "assistant_id", "TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'")
    },
    columnMigration(5, 6) {
        addColumnIfMissing("ConversationEntity", "truncate_index", "INTEGER NOT NULL DEFAULT -1")
    },
    columnMigration(7, 8) {
        addColumnIfMissing("ConversationEntity", "suggestions", "TEXT NOT NULL DEFAULT '[]'")
    },
    columnMigration(9, 10) {
        addColumnIfMissing("ConversationEntity", "is_pinned", "INTEGER NOT NULL DEFAULT 0")
    },
    columnMigration(17, 18) {
        addColumnIfMissing("GenMediaEntity", "type", "TEXT NOT NULL DEFAULT 'image_generation'")
        addColumnIfMissing("GenMediaEntity", "source_paths", "TEXT DEFAULT NULL")
    },
    columnMigration(18, 19) {
        addColumnIfMissing("ConversationEntity", "custom_system_prompt", "TEXT NOT NULL DEFAULT ''")
    },
    columnMigration(19, 20) {
        addColumnIfMissing("ConversationEntity", "mode_injection_ids", "TEXT NOT NULL DEFAULT '[]'")
        addColumnIfMissing("ConversationEntity", "lorebook_ids", "TEXT NOT NULL DEFAULT '[]'")
    },
    columnMigration(21, 22) {
        addColumnIfMissing("ConversationEntity", "workspace_cwd", "TEXT NOT NULL DEFAULT ''")
    },
    columnMigration(23, 24) {
        addColumnIfMissing("ConversationEntity", "folder_id", "TEXT NOT NULL DEFAULT ''")
        execSQL("CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)")
    },
    columnMigration(24, 25) {
        addColumnIfMissing("workspaces", "shell_compatibility_mode", "INTEGER NOT NULL DEFAULT 0")
    },
    columnMigration(26, 27) {
        addColumnIfMissing("ConversationEntity", "config", "TEXT NOT NULL DEFAULT ''")
    },
    columnMigration(31, 32) {
        addColumnIfMissing("workspaces", "type", "TEXT NOT NULL DEFAULT 'LOCAL'")
        addColumnIfMissing("workspaces", "ssh_config", "TEXT DEFAULT NULL")
    },
)

private fun columnMigration(
    from: Int,
    to: Int,
    action: SupportSQLiteDatabase.() -> Unit,
): Migration = object : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.action()
    }
}
