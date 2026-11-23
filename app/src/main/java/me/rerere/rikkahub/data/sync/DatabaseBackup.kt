package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.migrations.RestoreSchemaCompatibility
import java.io.File

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /** Room 无法打开时，仍由 SQLite 读取已提交的 WAL 并生成一致快照，不复制活动数据库文件。 */
    fun createSnapshot(context: Context, source: File, destination: File) {
        openReadOnly(context, source).use { database ->
            createSnapshot(database, destination)
        }
    }

    fun readUserVersion(context: Context, snapshot: File): Int =
        openReadOnly(context, snapshot).use { it.version }

    private fun openReadOnly(context: Context, file: File): SQLiteDatabase {
        require(file.isFile && file.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(file.absolutePath, SQLiteDatabase.OPEN_READONLY),
        )
        return SQLiteDatabase.openDatabase(configuration, null) {
            // 默认损坏处理器可能删除源文件；备份只能报告错误。
            error("Backup database is corrupt")
        }
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    /** The caller must pass an extracted staging copy, never the live/source database. */
    fun prepareForRestore(context: Context, databaseFile: File) {
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE),
        )
        SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            require(database.version in 1..39) { "Unsupported backup database version: ${database.version} (Beacon: 39)" }
            if (database.version == 39) {
                database.beginTransaction()
                try {
                    RestoreSchemaCompatibility.repair(database)
                    // Even a matching hash is not proof after cross-fork structural repairs.
                    // Absence makes Room validate EVERY target table before writing its identity.
                    // Do not write a new hash ourselves or lower/change user_version.
                    database.execSQL("DROP TABLE IF EXISTS `room_master_table`")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            checkpoint(database)
        }
        removeSidecars(databaseFile)
    }

    fun validateForeignKeys(database: SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) { "Backup database contains invalid foreign key references" }
        }
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}
