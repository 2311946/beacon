package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(37, 38)
        try {
            db.execSQL(
                """
                CREATE TABLE `memory_consolidation_new` (
                    `conversation_id` TEXT NOT NULL,
                    `consolidated_at` INTEGER NOT NULL,
                    `assistant_id` TEXT NOT NULL,
                    PRIMARY KEY(`conversation_id`, `assistant_id`),
                    FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            // 旧进度只属于会话 owner，群聊其他成员必须有独立的首次整理进度。
            db.execSQL(
                "INSERT INTO `memory_consolidation_new` (`conversation_id`, `consolidated_at`, `assistant_id`) " +
                    "SELECT m.`conversation_id`, m.`consolidated_at`, c.`assistant_id` " +
                    "FROM `memory_consolidation` AS m " +
                    "JOIN `conversationentity` AS c ON c.`id` = m.`conversation_id`"
            )
            db.execSQL("DROP TABLE `memory_consolidation`")
            db.execSQL("ALTER TABLE `memory_consolidation_new` RENAME TO `memory_consolidation`")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
