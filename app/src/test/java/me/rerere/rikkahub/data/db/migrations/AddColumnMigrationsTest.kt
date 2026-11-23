package me.rerere.rikkahub.data.db.migrations

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class AddColumnMigrationsTest {
    @Test
    fun `missing column is added and cursor is closed`() {
        val database = DatabaseFixture(mapOf("items" to listOf("id")))

        database.database.addColumnIfMissing("items", "label", "TEXT NOT NULL DEFAULT ''")

        assertEquals(listOf("PRAGMA table_info(`items`)"), database.queries)
        assertEquals(listOf("ALTER TABLE `items` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''"), database.statements)
        assertEquals(listOf("id", "label"), database.columns.getValue("items"))
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `existing column after unrelated columns does not write`() {
        val database = DatabaseFixture(mapOf("items" to listOf("id", "created_at", "label")))

        database.database.addColumnIfMissing("items", "label", "TEXT NOT NULL DEFAULT ''")

        assertEquals(listOf("PRAGMA table_info(`items`)"), database.queries)
        assertTrue(database.statements.isEmpty())
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `matching first column closes cursor before exhausting rows`() {
        val database = DatabaseFixture(mapOf("items" to listOf("label", "id")))

        database.database.addColumnIfMissing("items", "label", "TEXT")

        assertTrue(database.statements.isEmpty())
        assertEquals(1, database.cursors.single().moveCount)
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `column name matching ignores case`() {
        for ((existing, requested) in listOf("LaBeL" to "label", "label" to "LABEL")) {
            val database = DatabaseFixture(mapOf("items" to listOf(existing)))

            database.database.addColumnIfMissing("items", requested, "TEXT")

            assertTrue(database.statements.isEmpty())
            assertEquals(1, database.cursors.single().closeCount)
        }
    }

    @Test
    fun `table and column identifiers escape backticks`() {
        val database = DatabaseFixture(mapOf("odd`table" to emptyList()))

        database.database.addColumnIfMissing("odd`table", "odd`column", "TEXT DEFAULT NULL")

        assertEquals(listOf("PRAGMA table_info(`odd``table`)"), database.queries)
        assertEquals(
            listOf("ALTER TABLE `odd``table` ADD COLUMN `odd``column` TEXT DEFAULT NULL"),
            database.statements,
        )
        assertEquals(listOf("odd`column"), database.columns.getValue("odd`table"))
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `query SQL failure is propagated without executing DDL`() {
        val database = DatabaseFixture(mapOf("items" to emptyList()))
        val failure = IllegalStateException("PRAGMA failed")
        database.queryFailure = failure

        assertFailure(failure) {
            database.database.addColumnIfMissing("items", "label", "TEXT")
        }

        assertEquals(listOf("PRAGMA table_info(`items`)"), database.queries)
        assertTrue(database.statements.isEmpty())
        assertTrue(database.cursors.isEmpty())
    }

    @Test
    fun `DDL SQL failure is propagated after closing cursor`() {
        val database = DatabaseFixture(mapOf("items" to emptyList()))
        val failure = IllegalStateException("ALTER TABLE failed")
        database.execFailure = failure

        assertFailure(failure) {
            database.database.addColumnIfMissing("items", "label", "INVALID DEFINITION")
        }

        assertEquals(listOf("ALTER TABLE `items` ADD COLUMN `label` INVALID DEFINITION"), database.statements)
        assertTrue(database.columns.getValue("items").isEmpty())
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `cursor read failure is propagated and cursor is closed`() {
        val database = DatabaseFixture(mapOf("items" to listOf("id")))
        val failure = IllegalStateException("column read failed")
        database.readFailure = failure

        assertFailure(failure) {
            database.database.addColumnIfMissing("items", "label", "TEXT")
        }

        assertTrue(database.statements.isEmpty())
        assertEquals(1, database.cursors.single().closeCount)
    }

    @Test
    fun `missing name metadata is propagated and cursor is closed`() {
        val database = DatabaseFixture(mapOf("items" to listOf("id")))
        val failure = IllegalArgumentException("name column missing")
        database.columnIndexFailure = failure

        assertFailure(failure) {
            database.database.addColumnIfMissing("items", "label", "TEXT")
        }

        assertTrue(database.statements.isEmpty())
        assertEquals(1, database.cursors.single().closeCount)
    }
}

@RunWith(Parameterized::class)
class AddColumnMigrationDefinitionsTest(private val fixture: MigrationFixture) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun migrations(): Collection<Array<Any>> = expectedMigrations.map { arrayOf<Any>(it) }
    }

    @Test
    fun `first migration adds every expected column and preserves other DDL`() {
        assertMigrationInventory()
        val database = databaseFixture()

        migration().migrate(database.database)

        assertEquals(fixture.columns.map { it.pragma }, database.queries)
        assertEquals(fixture.columns.map { it.ddl } + fixture.otherDdl, database.statements)
        assertColumnsPresent(database)
        assertCursorsClosed(database, fixture.columns.size)
    }

    @Test
    fun `repeating migration skips existing columns but preserves other DDL`() {
        val database = databaseFixture()
        val migration = migration()
        migration.migrate(database.database)
        val firstStatements = database.statements.toList()
        database.statements.clear()

        migration.migrate(database.database)

        assertEquals(fixture.columns.map { it.ddl } + fixture.otherDdl, firstStatements)
        assertEquals(fixture.otherDdl, database.statements)
        assertEquals(fixture.columns.map { it.pragma }.let { it + it }, database.queries)
        assertColumnsPresent(database)
        assertCursorsClosed(database, fixture.columns.size * 2)
    }

    @Test
    fun `each independently preexisting column leaves all other missing columns to be added`() {
        for (existing in fixture.columns) {
            val database = databaseFixture(existing)

            migration().migrate(database.database)

            assertEquals(existing.column, fixture.columns.map { it.pragma }, database.queries)
            assertEquals(
                existing.column,
                fixture.columns.filterNot { it == existing }.map { it.ddl } + fixture.otherDdl,
                database.statements,
            )
            assertColumnsPresent(database)
            assertCursorsClosed(database, fixture.columns.size)
        }
    }

    private fun migration() = addColumnMigrations.single {
        it.startVersion == fixture.from && it.endVersion == fixture.to
    }

    private fun databaseFixture(existing: ColumnFixture? = null): DatabaseFixture {
        val tables = fixture.columns.map { it.table }.distinct().associateWith { table ->
            listOf("unrelated_column") + listOfNotNull(existing?.takeIf { it.table == table }?.column)
        }
        return DatabaseFixture(tables)
    }

    private fun assertColumnsPresent(database: DatabaseFixture) {
        for (table in fixture.columns.map { it.table }.distinct()) {
            val expected = setOf("unrelated_column") + fixture.columns.filter { it.table == table }.map { it.column }
            assertEquals(expected, database.columns.getValue(table).toSet())
            assertEquals(expected.size, database.columns.getValue(table).size)
        }
    }

    private fun assertCursorsClosed(database: DatabaseFixture, count: Int) {
        assertEquals(count, database.cursors.size)
        database.cursors.forEach { assertEquals(1, it.closeCount) }
    }

    private fun assertMigrationInventory() {
        assertEquals(13, expectedMigrations.size)
        assertEquals(16, expectedMigrations.sumOf { it.columns.size })
        assertEquals(
            expectedMigrations.map { it.from to it.to }.sortedBy { it.first },
            addColumnMigrations.map { it.startVersion to it.endVersion }.sortedBy { it.first },
        )
    }
}

data class MigrationFixture(
    val from: Int,
    val to: Int,
    val columns: List<ColumnFixture>,
    val otherDdl: List<String> = emptyList(),
) {
    override fun toString() = "$from->$to"
}

data class ColumnFixture(val table: String, val column: String, val ddl: String) {
    val pragma: String get() = "PRAGMA table_info(`$table`)"
}

private val expectedMigrations = listOf(
    MigrationFixture(3, 4, listOf(
        ColumnFixture("ConversationEntity", "usage", "ALTER TABLE `ConversationEntity` ADD COLUMN `usage` TEXT DEFAULT NULL"),
    )),
    MigrationFixture(4, 5, listOf(
        ColumnFixture("ConversationEntity", "assistant_id", "ALTER TABLE `ConversationEntity` ADD COLUMN `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e'"),
    )),
    MigrationFixture(5, 6, listOf(
        ColumnFixture("ConversationEntity", "truncate_index", "ALTER TABLE `ConversationEntity` ADD COLUMN `truncate_index` INTEGER NOT NULL DEFAULT -1"),
    )),
    MigrationFixture(7, 8, listOf(
        ColumnFixture("ConversationEntity", "suggestions", "ALTER TABLE `ConversationEntity` ADD COLUMN `suggestions` TEXT NOT NULL DEFAULT '[]'"),
    )),
    MigrationFixture(9, 10, listOf(
        ColumnFixture("ConversationEntity", "is_pinned", "ALTER TABLE `ConversationEntity` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0"),
    )),
    MigrationFixture(17, 18, listOf(
        ColumnFixture("GenMediaEntity", "type", "ALTER TABLE `GenMediaEntity` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'image_generation'"),
        ColumnFixture("GenMediaEntity", "source_paths", "ALTER TABLE `GenMediaEntity` ADD COLUMN `source_paths` TEXT DEFAULT NULL"),
    )),
    MigrationFixture(18, 19, listOf(
        ColumnFixture("ConversationEntity", "custom_system_prompt", "ALTER TABLE `ConversationEntity` ADD COLUMN `custom_system_prompt` TEXT NOT NULL DEFAULT ''"),
    )),
    MigrationFixture(19, 20, listOf(
        ColumnFixture("ConversationEntity", "mode_injection_ids", "ALTER TABLE `ConversationEntity` ADD COLUMN `mode_injection_ids` TEXT NOT NULL DEFAULT '[]'"),
        ColumnFixture("ConversationEntity", "lorebook_ids", "ALTER TABLE `ConversationEntity` ADD COLUMN `lorebook_ids` TEXT NOT NULL DEFAULT '[]'"),
    )),
    MigrationFixture(21, 22, listOf(
        ColumnFixture("ConversationEntity", "workspace_cwd", "ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_cwd` TEXT NOT NULL DEFAULT ''"),
    )),
    MigrationFixture(
        23,
        24,
        listOf(
            ColumnFixture("ConversationEntity", "folder_id", "ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"),
        ),
        listOf(
            "CREATE TABLE IF NOT EXISTS `conversation_folder` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL DEFAULT 0, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)",
        ),
    ),
    MigrationFixture(24, 25, listOf(
        ColumnFixture("workspaces", "shell_compatibility_mode", "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"),
    )),
    MigrationFixture(26, 27, listOf(
        ColumnFixture("ConversationEntity", "config", "ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''"),
    )),
    MigrationFixture(31, 32, listOf(
        ColumnFixture("workspaces", "type", "ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'"),
        ColumnFixture("workspaces", "ssh_config", "ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT DEFAULT NULL"),
    )),
)

private class DatabaseFixture(initialColumns: Map<String, List<String>>) {
    val columns = initialColumns.mapValues { it.value.toMutableList() }.toMutableMap()
    val queries = mutableListOf<String>()
    val statements = mutableListOf<String>()
    val cursors = mutableListOf<CursorFixture>()
    var queryFailure: RuntimeException? = null
    var execFailure: RuntimeException? = null
    var readFailure: RuntimeException? = null
    var columnIndexFailure: RuntimeException? = null

    val database: SupportSQLiteDatabase = interfaceProxy(SupportSQLiteDatabase::class.java) { method, args ->
        when (method.name) {
            "query" -> {
                check(method.parameterTypes.first() == String::class.java)
                val sql = args[0] as String
                queries += sql
                queryFailure?.let { throw it }
                val match = Regex("PRAGMA table_info\\(`((?:``|[^`])*)`\\)").matchEntire(sql)
                    ?: error("意外的查询：$sql")
                val table = match.groupValues[1].replace("``", "`")
                val cursor = CursorFixture(columns.getValue(table).toList(), readFailure, columnIndexFailure)
                cursors += cursor
                cursor.cursor
            }
            "execSQL" -> {
                check(method.parameterTypes.first() == String::class.java)
                if (args.size == 2) {
                    check((args[1] as Array<*>).isEmpty())
                }
                val sql = args[0] as String
                statements += sql
                check(cursors.all { it.closeCount == 1 })
                execFailure?.let { throw it }
                val match = Regex("ALTER TABLE `((?:``|[^`])*)` ADD COLUMN `((?:``|[^`])*)` .+")
                    .matchEntire(sql)
                if (match != null) {
                    val table = match.groupValues[1].replace("``", "`")
                    val column = match.groupValues[2].replace("``", "`")
                    val names = columns.getValue(table)
                    check(names.none { it.equals(column, ignoreCase = true) }) { "重复添加字段：$table.$column" }
                    names += column
                } else {
                    check(sql.startsWith("CREATE TABLE IF NOT EXISTS ") || sql.startsWith("CREATE INDEX IF NOT EXISTS "))
                }
                null
            }
            else -> error("意外的数据库调用：${method.name}")
        }
    }
}

private class CursorFixture(
    private val names: List<String>,
    private val readFailure: RuntimeException?,
    private val columnIndexFailure: RuntimeException?,
) {
    var closeCount = 0
        private set
    var moveCount = 0
        private set
    private var position = -1

    val cursor: Cursor = interfaceProxy(Cursor::class.java) { method, args ->
        when (method.name) {
            "getColumnIndexOrThrow" -> {
                check(closeCount == 0)
                check(args.single() == "name")
                columnIndexFailure?.let { throw it }
                1
            }
            "moveToNext" -> {
                check(closeCount == 0)
                moveCount++
                position++
                position < names.size
            }
            "getString" -> {
                check(closeCount == 0)
                check(args.single() == 1)
                readFailure?.let { throw it }
                names[position]
            }
            "close" -> {
                closeCount++
                null
            }
            "isClosed" -> closeCount != 0
            else -> error("意外的游标调用：${method.name}")
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> interfaceProxy(type: Class<T>, handler: (Method, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.single()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "测试代理(${type.simpleName})"
            else -> handler(method, args ?: emptyArray())
        }
    } as T

private fun assertFailure(expected: RuntimeException, action: () -> Unit) {
    try {
        action()
        fail("预期异常应向上传递")
    } catch (actual: RuntimeException) {
        assertSame(expected, actual)
    }
}
