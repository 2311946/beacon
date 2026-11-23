package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddColumnMigrationsTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun emptyDatabasesMatchEveryTargetSchema() {
        fixtures.forEach { fixture ->
            migrateAndValidate(fixture, "empty", emptyList())
        }
    }

    @Test
    fun everyIndividuallyExistingColumnMatchesTargetSchema() {
        fixtures.forEach { fixture ->
            fixture.columns.forEachIndexed { index, ddl ->
                migrateAndValidate(fixture, "existing-column-$index", listOf(ddl))
            }
        }
    }

    @Test
    fun allExistingColumnsMatchEveryTargetSchema() {
        fixtures.forEach { fixture ->
            migrateAndValidate(fixture, "existing-all", fixture.columns)
        }
    }

    private fun migrateAndValidate(fixture: Fixture, scenario: String, existingColumns: List<String>) {
        val name = "add-column-${fixture.from}-${fixture.to}-$scenario"
        helper.createDatabase(name, fixture.from).use { db ->
            existingColumns.forEach { ddl -> db.execSQL(ddl) }
        }

        helper.runMigrationsAndValidate(name, fixture.to, true, *addColumnMigrations).use { db ->
            if (fixture.to == 24) {
                db.query("PRAGMA index_list(`conversation_folder`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "index_conversation_folder_assistant_id") {
                            assertEquals(0, cursor.getInt(uniqueIndex))
                            found = true
                        }
                    }
                    assertTrue("$name: conversation_folder 缺少 assistant_id 索引", found)
                }
                db.query("PRAGMA index_info(`index_conversation_folder_assistant_id`)").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("assistant_id", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    assertFalse(cursor.moveToNext())
                }
            }
        }
    }

    private data class Fixture(val from: Int, val to: Int, val columns: List<String>)

    // 独立声明历史 DDL，避免测试准备与生产侧的加列检查共享实现。
    private val fixtures = listOf(
        Fixture(
            3,
            4,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `usage` TEXT DEFAULT NULL"),
        ),
        Fixture(
            4,
            5,
            listOf(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `assistant_id` " +
                    "TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'",
            ),
        ),
        Fixture(
            5,
            6,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `truncate_index` INTEGER NOT NULL DEFAULT -1"),
        ),
        Fixture(
            7,
            8,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `suggestions` TEXT NOT NULL DEFAULT '[]'"),
        ),
        Fixture(
            9,
            10,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0"),
        ),
        Fixture(
            17,
            18,
            listOf(
                "ALTER TABLE `GenMediaEntity` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'image_generation'",
                "ALTER TABLE `GenMediaEntity` ADD COLUMN `source_paths` TEXT DEFAULT NULL",
            ),
        ),
        Fixture(
            18,
            19,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `custom_system_prompt` TEXT NOT NULL DEFAULT ''"),
        ),
        Fixture(
            19,
            20,
            listOf(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `mode_injection_ids` TEXT NOT NULL DEFAULT '[]'",
                "ALTER TABLE `ConversationEntity` ADD COLUMN `lorebook_ids` TEXT NOT NULL DEFAULT '[]'",
            ),
        ),
        Fixture(
            21,
            22,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_cwd` TEXT NOT NULL DEFAULT ''"),
        ),
        Fixture(
            23,
            24,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"),
        ),
        Fixture(
            24,
            25,
            listOf("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"),
        ),
        Fixture(
            26,
            27,
            listOf("ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''"),
        ),
        Fixture(
            31,
            32,
            listOf(
                "ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'",
                "ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT DEFAULT NULL",
            ),
        ),
    )
}
