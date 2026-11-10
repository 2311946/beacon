package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `autonomous_activity` (
                    `id` TEXT NOT NULL,
                    `assistant_id` TEXT NOT NULL,
                    `assistant_name` TEXT NOT NULL,
                    `conversation_id` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `decision` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_autonomous_activity_assistant_id_created_at` " +
                    "ON `autonomous_activity` (`assistant_id`, `created_at`)"
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
