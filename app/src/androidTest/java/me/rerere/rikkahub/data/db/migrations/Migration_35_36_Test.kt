package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_35_36_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun beacon34MigratesThroughCompositeIndexAndMountColumn() {
        val name = "migration-34-36-test"
        helper.createDatabase(name, 34).apply {
            execSQL("INSERT INTO autonomous_activity " +
                "(id, assistant_id, assistant_name, conversation_id, created_at, content, decision) " +
                "VALUES ('activity', 'assistant', '角色', 'conv', 3, '阅读', 'manual')")
            close()
        }
        helper.runMigrationsAndValidate(name, 36, true, Migration_34_35, Migration_35_36).use { db ->
            db.query("SELECT content FROM autonomous_activity WHERE id='activity'").use {
                assertTrue(it.moveToFirst())
                assertEquals("阅读", it.getString(0))
            }
            db.query("PRAGMA index_list(`message_node`)").use {
                val indexName = it.getColumnIndexOrThrow("name")
                var found = false
                while (it.moveToNext()) {
                    if (it.getString(indexName) == "index_message_node_conversation_id_node_index") found = true
                }
                assertTrue(found)
            }
        }
    }

    @Test
    fun existingWorkspacesReceiveEmptyMountsWithoutChangingOtherData() {
        val name = "migration-35-36-test"
        helper.createDatabase(name, 35).apply {
            execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, tool_approvals) " +
                    "VALUES ('local', 'Existing', 'local-root', 'READY', 100, 200, '{\"workspace_shell\":true}')"
            )
            execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, type, ssh_config) " +
                    "VALUES ('ssh', 'Remote', 'ssh-root', 'READY', 100, 200, 'SSH', '{\"remoteDir\":\"/srv\"}')"
            )
            execSQL(
                "INSERT INTO assistant_calendar_day (assistant_id, date, entries_json, revision) " +
                    "VALUES ('assistant', '2026-11-21', '[]', 7)"
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 36, true, Migration_35_36).use { db ->
            db.query("SELECT name, root, shell_status, updated_at, tool_approvals, storage_mounts FROM workspaces WHERE id = 'local'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Existing", it.getString(0))
                assertEquals("local-root", it.getString(1))
                assertEquals("READY", it.getString(2))
                assertEquals(200L, it.getLong(3))
                assertEquals("{\"workspace_shell\":true}", it.getString(4))
                assertEquals("[]", it.getString(5))
            }
            db.query("SELECT type, ssh_config, storage_mounts FROM workspaces WHERE id = 'ssh'").use {
                assertTrue(it.moveToFirst())
                assertEquals("SSH", it.getString(0))
                assertEquals("{\"remoteDir\":\"/srv\"}", it.getString(1))
                assertEquals("[]", it.getString(2))
            }
            db.query("SELECT revision FROM assistant_calendar_day WHERE assistant_id = 'assistant'").use {
                assertTrue(it.moveToFirst())
                assertEquals(7L, it.getLong(0))
            }
            db.execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) " +
                    "VALUES ('new', 'New', 'new-root', 'DISABLED', 300, 300)"
            )
            db.query("SELECT storage_mounts FROM workspaces WHERE id = 'new'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[]", it.getString(0))
            }
        }
    }
}
