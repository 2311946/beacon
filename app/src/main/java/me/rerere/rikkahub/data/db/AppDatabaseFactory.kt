package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.createMessageFtsTables
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.Migration_37_38
import me.rerere.rikkahub.data.db.migrations.Migration_38_39
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.RestoreSchemaCompatibility
import me.rerere.rikkahub.data.db.migrations.addColumnMigrations
import java.io.File

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(
        context: Context,
        name: String = SQLiteConfiguration.DATABASE_NAME,
        restoringBackup: Boolean = false,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .apply {
                if (restoringBackup) {
                    require(File(name).isAbsolute && name != context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME).absolutePath) {
                        "Backup validation requires an isolated database copy"
                    }
                }
            }
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                *addColumnMigrations,
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_27_28,
                Migration_30_31,
                Migration_32_33,
                Migration_33_34,
                Migration_34_35,
                Migration_35_36,
                Migration_36_37,
                Migration_37_38,
                Migration_38_39,
            )
            .apply {
                if (restoringBackup) {
                    // Forks reuse user_version for different schemas. Only the staged copy uses
                    // these structural migrations; live databases keep their historical chain.
                    addMigrations(*(32 until 39).map { from ->
                        object : Migration(from, 39) {
                            override fun migrate(db: SupportSQLiteDatabase) {
                                RestoreSchemaCompatibility.repair(db)
                            }
                        }
                    }.toTypedArray())
                }
            }
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // 旧版本搜索用 jieba 分词，会把词典解压到这里（约 14MB），现在不再需要
                    if (!restoringBackup) {
                        File(context.filesDir, "simple_dict").takeIf { it.exists() }?.deleteRecursively()
                    }
                    createMessageFtsTables(db)
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
