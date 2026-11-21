package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

/** Replaces the conversation-only message index with the ordering-aware composite index. */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            Log.i(TAG, "migrate: 34 to 35 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
