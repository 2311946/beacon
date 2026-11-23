package me.rerere.rikkahub.data.db.migrations

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSchemaCompatibilityTest {
    @Test
    fun `missing core tables are rejected before any repair writes`() {
        for (table in coreTables) {
            val fixture = RestoreDatabaseFixture().apply { columns.remove(table) }

            assertRejected(table) { RestoreSchemaCompatibility.repair(fixture.database) }

            assertTrue(table, fixture.statements.isEmpty())
            fixture.assertCursorsClosed()
        }
    }

    @Test
    fun `every created table and index matches exported schema 39`() {
        val fixture = RestoreDatabaseFixture().apply { repairTables.forEach { columns.remove(it) } }

        RestoreSchemaCompatibility.repair(fixture.database)

        val expected = (repairTables.flatMap { table ->
            val entity = schema39.getValue(table)
            listOf(entity.createSql) + entity.indices
        } + schema39.getValue("message_node").indices).map(::canonicalDdl).toSet()
        val actual = fixture.statements.map { it.sql }.filter { it.startsWith("CREATE", ignoreCase = true) }
            .map(::canonicalDdl)
        assertEquals(expected, actual.toSet())
        assertEquals(schema39.keys, fixture.columns.keys)
        fixture.assertCursorsClosed()
    }

    @Test
    fun `existing fields are never added a second time`() {
        val fixture = RestoreDatabaseFixture()

        repeat(2) { RestoreSchemaCompatibility.repair(fixture.database) }

        assertTrue(fixture.statements.none { canonicalSql(it.sql).contains("add column") })
        assertEquals(schema39.mapValues { it.value.columns.toSet() }, fixture.columns.mapValues { it.value.toSet() })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `missing storage and embedding fields are added exactly once`() {
        val missing = mapOf(
            "workspaces" to listOf("storage_mounts"),
            "memory_chunk" to listOf("embedding", "embedding_profile", "embedding_dimensions"),
        )
        val fixture = RestoreDatabaseFixture().apply {
            missing.forEach { (table, names) -> columns.getValue(table).removeAll(names.toSet()) }
        }

        repeat(2) { RestoreSchemaCompatibility.repair(fixture.database) }

        assertEquals(
            setOf(
                "alter table workspaces add column storage_mounts text not null default '[]'",
                "alter table memory_chunk add column embedding blob",
                "alter table memory_chunk add column embedding_profile text not null default ''",
                "alter table memory_chunk add column embedding_dimensions integer not null default 0",
            ),
            fixture.statements.map { canonicalSql(it.sql) }.filter { it.contains("add column") }.toSet(),
        )
        assertEquals(4, fixture.statements.count { canonicalSql(it.sql).contains("add column") })
        assertEquals(schema39.mapValues { it.value.columns.toSet() }, fixture.columns.mapValues { it.value.toSet() })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `existing memory metadata and provenance are not overwritten`() {
        val fixture = RestoreDatabaseFixture()
        val file = RestoreMemoryFile("owner", "notes/existing.md", "不可覆盖的正文", 42L)
        fixture.memoryFiles += file
        fixture.existingMetadata += file.key

        RestoreSchemaCompatibility.repair(fixture.database)

        assertTrue(fixture.memoryInserts().isEmpty())
        assertEquals(setOf(file.key), fixture.existingMetadata)
        assertTrue(fixture.statements.none { canonicalSql(it.sql).startsWith("update memory_") })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `only files without metadata receive three mutually consistent backfill rows`() {
        val fixture = RestoreDatabaseFixture()
        val existing = RestoreMemoryFile("owner", "notes/existing.md", "已有元数据", 1L)
        val missing = listOf(
            RestoreMemoryFile("owner", "notes/shared.md", "助手记忆正文\n保留原文", 42L),
            RestoreMemoryFile("__global__", "notes/shared.md", "显式全局记忆", 84L),
        )
        fixture.memoryFiles += listOf(existing) + missing
        fixture.existingMetadata += existing.key

        RestoreSchemaCompatibility.repair(fixture.database)
        val firstInserts = fixture.memoryInserts().toList()
        RestoreSchemaCompatibility.repair(fixture.database)

        assertEquals(6, firstInserts.size)
        assertEquals(firstInserts, fixture.memoryInserts())
        for (file in missing) {
            val rows = firstInserts.filter { it.arguments.take(2) == listOf(file.namespace, file.path) }
            assertEquals(3, rows.size)
            val metadata = rows.single { it.insertTable == "memory_metadata" }
            val source = rows.single { it.insertTable == "memory_source" }
            val job = rows.single { it.insertTable == "memory_index_job" }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val revision = metadata.arguments[4] as String
            assertFalse(revision.isBlank())
            assertEquals(
                listOf(file.namespace, file.path, if (file.namespace == "__global__") "EXPLICIT_GLOBAL" else "ASSISTANT", hash, revision, file.updatedAt),
                metadata.arguments,
            )
            assertEquals(revision, source.arguments[3])
            assertFalse((source.arguments[2] as String).isBlank())
            val sourceJson = Json.parseToJsonElement(source.arguments[4] as String).jsonObject
            assertEquals("UNKNOWN", sourceJson.getValue("kind").jsonPrimitive.content)
            assertEquals(listOf(file.namespace, file.path, revision, hash, file.updatedAt), job.arguments)
            assertTrue(canonicalSql(metadata.sql).contains("'unknown','active'"))
            assertTrue(canonicalSql(source.sql).contains("'unknown','current'"))
            assertTrue(canonicalSql(source.sql).contains("null,null"))
        }
        assertEquals(setOf(existing.key) + missing.map { it.key }, fixture.existingMetadata)
        fixture.assertCursorsClosed()
    }

    @Test
    fun `exact two column legacy consolidation migrates once using conversation owner`() {
        val fixture = RestoreDatabaseFixture().apply {
            columns["memory_consolidation"] = mutableListOf("conversation_id", "consolidated_at")
        }

        RestoreSchemaCompatibility.repair(fixture.database)
        val migrated = fixture.statements.filter { canonicalSql(it.sql).contains("memory_consolidation") }
        RestoreSchemaCompatibility.repair(fixture.database)

        val copy = migrated.single { it.insertTable == "restore_memory_consolidation_new" }
        val sql = canonicalSql(copy.sql)
        assertTrue(sql.contains("c.assistant_id"))
        assertTrue(sql.contains("join conversationentity"))
        assertTrue(sql.contains("c.id=m.conversation_id"))
        assertEquals(1, fixture.statements.count { canonicalSql(it.sql).startsWith("drop table memory_consolidation") })
        assertEquals(schema39.getValue("memory_consolidation").columns, fixture.columns.getValue("memory_consolidation"))
        fixture.assertCursorsClosed()
    }

    @Test
    fun `legacy consolidation with additional or missing columns is rejected without rebuilding`() {
        for (columns in listOf(
            listOf("conversation_id", "consolidated_at", "unrecognized"),
            listOf("conversation_id"),
            listOf("consolidated_at"),
        )) {
            val fixture = RestoreDatabaseFixture().apply {
                this.columns["memory_consolidation"] = columns.toMutableList()
            }

            assertRejected("memory_consolidation") { RestoreSchemaCompatibility.repair(fixture.database) }

            assertTrue(fixture.statements.none { canonicalSql(it.sql).startsWith("drop table") })
            assertFalse(fixture.columns.containsKey("restore_memory_consolidation_new"))
            fixture.assertCursorsClosed()
        }
    }

    @Test
    fun `two legacy column names do not excuse incompatible type nullability default or primary key`() {
        for (invalid in listOf(
            listOf(1, "consolidated_at", "TEXT", 1, null, 0, 0),
            listOf(1, "consolidated_at", "INTEGER", 0, null, 0, 0),
            listOf(1, "consolidated_at", "INTEGER", 1, "0", 0, 0),
            listOf(1, "consolidated_at", "INTEGER", 1, null, 1, 0),
        )) {
            val fixture = RestoreDatabaseFixture().apply {
                columns["memory_consolidation"] = mutableListOf("conversation_id", "consolidated_at")
                legacyColumnOverride = invalid
            }

            assertRejected("memory_consolidation") { RestoreSchemaCompatibility.repair(fixture.database) }

            assertFalse(fixture.columns.containsKey("restore_memory_consolidation_new"))
            fixture.assertCursorsClosed()
        }
    }

    @Test
    fun `legacy consolidation with hidden extra column is rejected before rebuilding`() {
        val fixture = RestoreDatabaseFixture().apply {
            columns["memory_consolidation"] = mutableListOf("conversation_id", "consolidated_at")
            hiddenConsolidationColumn = true
        }

        assertRejected("memory_consolidation") { RestoreSchemaCompatibility.repair(fixture.database) }

        assertFalse(fixture.columns.containsKey("restore_memory_consolidation_new"))
        fixture.assertCursorsClosed()
    }

    @Test
    fun `legacy consolidation with orphaned conversation owner is rejected before rebuilding`() {
        val fixture = RestoreDatabaseFixture().apply {
            columns["memory_consolidation"] = mutableListOf("conversation_id", "consolidated_at")
            orphanedConsolidation = true
        }

        assertRejected("memory_consolidation") { RestoreSchemaCompatibility.repair(fixture.database) }

        assertFalse(fixture.columns.containsKey("restore_memory_consolidation_new"))
        assertTrue(fixture.statements.none { canonicalSql(it.sql).startsWith("drop table") })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `consolidation with assistant id is not rebuilt`() {
        val fixture = RestoreDatabaseFixture()

        RestoreSchemaCompatibility.repair(fixture.database)

        assertTrue(fixture.statements.none {
            val sql = canonicalSql(it.sql)
            sql.contains("restore_memory_consolidation_new") || sql.startsWith("drop table memory_consolidation")
        })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `existing usage records never trigger historical backfill`() {
        val fixture = RestoreDatabaseFixture()

        repeat(2) { RestoreSchemaCompatibility.repair(fixture.database) }

        assertTrue(fixture.statements.none { it.insertTable == "usage_record" })
        fixture.assertCursorsClosed()
    }

    @Test
    fun `missing usage table triggers historical backfill only once`() {
        val fixture = RestoreDatabaseFixture().apply { columns.remove("usage_record") }

        repeat(2) { RestoreSchemaCompatibility.repair(fixture.database) }

        val backfill = fixture.statements.single { it.insertTable == "usage_record" }
        val sql = canonicalSql(backfill.sql)
        assertTrue(sql.contains("from message_node"))
        assertTrue(sql.contains("json_each("))
        assertTrue(sql.contains("json_valid("))
        assertTrue(sql.contains("'assistant'"))
        assertTrue(sql.contains("'chat'"))
        assertTrue(sql.contains("$.usage.prompttokens"))
        assertTrue(sql.contains("$.usage.completiontokens"))
        assertTrue(sql.contains("$.usage.cachedtokens"))
        fixture.assertCursorsClosed()
    }
}

private data class RestoreSchemaEntity(val createSql: String, val columns: List<String>, val indices: List<String>)

private val schema39: Map<String, RestoreSchemaEntity> by lazy {
    val classes = File(RestoreSchemaCompatibilityTest::class.java.protectionDomain.codeSource.location.toURI())
    val relativePath = "schemas/me.rerere.rikkahub.data.db.AppDatabase/39.json"
    val schema = generateSequence(classes) { it.parentFile }
        .flatMap { sequenceOf(File(it, relativePath), File(it, "app/$relativePath")) }
        .firstOrNull { it.isFile }
        ?: error("无法从测试class路径定位导出的39版schema：$classes")
    val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        .getValue("entities").jsonArray
    entities.associate { element ->
        val entity = element.jsonObject
        val name = entity.getValue("tableName").jsonPrimitive.content
        fun expand(sql: String) = sql.replace("\${TABLE_NAME}", name)
        name to RestoreSchemaEntity(
            expand(entity.getValue("createSql").jsonPrimitive.content),
            entity.getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content },
            entity["indices"]?.jsonArray.orEmpty().map { expand(it.jsonObject.getValue("createSql").jsonPrimitive.content) },
        )
    }
}

private val coreTables = setOf("ConversationEntity", "message_node", "memory_file")
private val repairTables = setOf(
    "usage_record", "memory_metadata", "memory_source", "memory_chunk", "memory_index_job",
    "memory_consolidation", "autonomous_activity", "assistant_calendar_day",
)

private data class RestoreMemoryFile(val namespace: String, val path: String, val content: String, val updatedAt: Long) {
    val key get() = namespace to path
}

private data class RestoreStatement(val sql: String, val arguments: List<Any?>) {
    val insertTable get() = Regex("insert(?: or ignore)? into ([a-z_]+)").find(canonicalSql(sql))?.groupValues?.get(1)
}

private class RestoreDatabaseFixture {
    val columns = schema39.mapValues { it.value.columns.toMutableList() }.toMutableMap()
    val statements = mutableListOf<RestoreStatement>()
    val cursors = mutableListOf<RestoreCursorFixture>()
    val memoryFiles = mutableListOf<RestoreMemoryFile>()
    val existingMetadata = mutableSetOf<Pair<String, String>>()
    var orphanedConsolidation = false
    var hiddenConsolidationColumn = false
    var legacyColumnOverride: List<Any?> = emptyList()

    val database: SupportSQLiteDatabase = restoreProxy(SupportSQLiteDatabase::class.java) { method, args ->
        when (method.name) {
            "query" -> {
                check(method.parameterTypes.first() == String::class.java)
                val sql = canonicalSql(args[0] as String)
                val bindings = if (args.size > 1) (args[1] as Array<*>).toList() else emptyList()
                val result = query(sql, bindings)
                cursors += result
                result.cursor
            }
            "execSQL" -> {
                val statement = RestoreStatement(args[0] as String, if (args.size > 1) (args[1] as Array<*>).toList() else emptyList())
                statements += statement
                execute(statement)
                null
            }
            "beginTransaction", "setTransactionSuccessful", "endTransaction" -> null
            else -> throw AssertionError("意外的数据库调用：${method.name}")
        }
    }

    fun memoryInserts() = statements.filter { it.insertTable in setOf("memory_metadata", "memory_source", "memory_index_job") }

    fun assertCursorsClosed() = cursors.forEach { assertEquals("查询游标必须恰好关闭一次", 1, it.closeCount) }

    private fun query(sql: String, bindings: List<Any?>): RestoreCursorFixture {
        if (sql.contains("sqlite_master")) {
            val table = bindings.firstOrNull() as? String
                ?: Regex("name='([^']+)'").find(sql)?.groupValues?.get(1)
                ?: error("未识别的表查询：$sql")
            return RestoreCursorFixture(listOf("name"), columns.keys.filter { it.equals(table, ignoreCase = true) }.map { listOf(it) })
        }
        if (sql.startsWith("pragma table_info(") || sql.startsWith("pragma table_xinfo(")) {
            val table = sql.substringAfter('(').substringBefore(')')
            val names = columns.entries.firstOrNull { it.key.equals(table, ignoreCase = true) }?.value.orEmpty() +
                if (sql.startsWith("pragma table_xinfo(") && hiddenConsolidationColumn) listOf("hidden_extra") else emptyList()
            val legacy = table == "memory_consolidation" && "assistant_id" !in names
            return RestoreCursorFixture(
                listOf("cid", "name", "type", "notnull", "dflt_value", "pk", "hidden"),
                names.mapIndexed { index, name ->
                    if (legacy && name == "consolidated_at" && legacyColumnOverride.isNotEmpty()) {
                        legacyColumnOverride
                    } else {
                        listOf(
                            index, name, if (name == "consolidated_at") "INTEGER" else "TEXT", 1, null,
                            if (name == "conversation_id") 1 else if (!legacy && name == "assistant_id") 2 else 0, 0,
                        )
                    }
                },
            )
        }
        if (sql.startsWith("select (select count(*) from memory_consolidation)=")) {
            return RestoreCursorFixture(listOf("matched"), listOf(listOf(1)))
        }
        if (sql.contains("memory_consolidation") && sql.contains("join")) {
            assertTrue("必须识别会话不存在的整理进度", sql.contains("c.id is null"))
            assertTrue("必须识别owner为空的整理进度", sql.contains("c.assistant_id is null"))
            return RestoreCursorFixture(listOf("conversation_id"), if (orphanedConsolidation) listOf(listOf("orphan")) else emptyList())
        }
        if (sql.contains("from memory_file")) {
            assertTrue("必须仅查询缺metadata的memory_file", sql.contains("left join memory_metadata"))
            assertTrue("metadata关联必须包含namespace", sql.contains("m.assistant_id=f.assistant_id"))
            assertTrue("metadata关联必须包含path", sql.contains("m.path=f.path"))
            assertTrue("必须筛选metadata不存在的文件", sql.contains("where m.assistant_id is null"))
            return RestoreCursorFixture(
                listOf("assistant_id", "path", "content", "updated_at"),
                memoryFiles.filterNot { it.key in existingMetadata }.map { listOf(it.namespace, it.path, it.content, it.updatedAt) },
            )
        }
        throw AssertionError("意外的查询：$sql")
    }

    private fun execute(statement: RestoreStatement) {
        val sql = canonicalSql(statement.sql)
        val create = Regex("create table(?: if not exists)? ([a-z_]+)\\(").find(sql)
        if (create != null) {
            val table = create.groupValues[1]
            val actualName = schema39.keys.firstOrNull { it.equals(table, ignoreCase = true) } ?: table
            val schemaTable = if (table == "restore_memory_consolidation_new") "memory_consolidation" else actualName
            columns.putIfAbsent(actualName, schema39.getValue(schemaTable).columns.toMutableList())
            return
        }
        if (sql.startsWith("create index") || sql.startsWith("create unique index")) return
        if (sql == "drop index if exists index_message_node_conversation_id") return
        val add = Regex("alter table ([a-z_]+) add column ([a-z_]+) ").find(sql)
        if (add != null) {
            val table = columns.keys.single { it.equals(add.groupValues[1], ignoreCase = true) }
            val names = columns.getValue(table)
            val name = add.groupValues[2]
            check(names.none { it.equals(name, ignoreCase = true) }) { "重复添加字段：$table.$name" }
            names += name
            return
        }
        if (sql == "drop table memory_consolidation") {
            checkNotNull(columns.remove("memory_consolidation"))
            return
        }
        if (sql == "alter table restore_memory_consolidation_new rename to memory_consolidation") {
            columns["memory_consolidation"] = checkNotNull(columns.remove("restore_memory_consolidation_new"))
            return
        }
        when (statement.insertTable) {
            "memory_metadata" -> {
                val key = statement.arguments[0] as String to statement.arguments[1] as String
                check(existingMetadata.add(key)) { "覆盖已有metadata：$key" }
            }
            "memory_source", "memory_index_job", "restore_memory_consolidation_new", "usage_record" -> Unit
            else -> throw AssertionError("意外的写入：${statement.sql}")
        }
    }
}

private class RestoreCursorFixture(private val names: List<String>, private val rows: List<List<Any?>>) {
    var closeCount = 0
        private set
    private var position = -1
    val cursor: Cursor = restoreProxy(Cursor::class.java) { method, args ->
        if (method.name != "close" && method.name != "isClosed") check(closeCount == 0)
        when (method.name) {
            "getColumnIndexOrThrow" -> names.indexOf(args.single() as String).also { check(it >= 0) }
            "getColumnIndex" -> names.indexOf(args.single() as String)
            "moveToFirst" -> { position = 0; rows.isNotEmpty() }
            "moveToNext" -> { position++; position < rows.size }
            "getString" -> rows[position][args.single() as Int]?.toString()
            "getLong" -> (rows[position][args.single() as Int] as Number).toLong()
            "getInt" -> (rows[position][args.single() as Int] as Number).toInt()
            "isNull" -> rows[position][args.single() as Int] == null
            "getCount" -> rows.size
            "close" -> { closeCount++; null }
            "isClosed" -> closeCount != 0
            else -> throw AssertionError("意外的游标调用：${method.name}")
        }
    }
}

private fun canonicalSql(sql: String): String = sql.trim().replace("`", "").lowercase()
    .replace(Regex("\\s+"), " ").replace(Regex("\\s*([(),=])\\s*"), "$1")

private fun canonicalDdl(sql: String): String = canonicalSql(sql).replace(" if not exists", "")

private fun assertRejected(context: String, action: () -> Unit) {
    val failure = runCatching(action).exceptionOrNull()
    assertNotNull("必须拒绝不支持的结构：$context", failure)
    assertTrue("应由repair明确拒绝，而不是fixture失败：$failure", failure is IllegalStateException || failure is IllegalArgumentException)
    assertTrue("拒绝原因必须指出目标表：$failure", failure?.message.orEmpty().contains(context, ignoreCase = true))
}

@Suppress("UNCHECKED_CAST")
private fun <T> restoreProxy(type: Class<T>, handler: (Method, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.single()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "恢复测试代理(${type.simpleName})"
            else -> handler(method, args ?: emptyArray())
        }
    } as T
