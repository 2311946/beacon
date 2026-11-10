package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `assistant_calendar_day` (
                    `assistant_id` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    `entries_json` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`assistant_id`, `date`)
                )
                """.trimIndent()
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
