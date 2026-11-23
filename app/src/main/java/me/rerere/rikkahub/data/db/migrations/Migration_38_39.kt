package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(38, 39)
        try {
            db.addColumnIfMissing("memory_chunk", "embedding", "BLOB")
            db.addColumnIfMissing("memory_chunk", "embedding_profile", "TEXT NOT NULL DEFAULT ''")
            db.addColumnIfMissing("memory_chunk", "embedding_dimensions", "INTEGER NOT NULL DEFAULT 0")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
