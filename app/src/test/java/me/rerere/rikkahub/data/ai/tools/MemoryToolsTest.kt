package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MemoryCategory
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.model.MemoryScopeKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.MemorySourceStatus
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {
    private val dao = FakeMemoryDAO()
    private val repository = MemoryRepository(dao)
    private val tools = buildMemoryTools(repository, "assistant").associateBy { it.name }

    private val contentWrites = listOf(MemoryToolNames.WRITE, MemoryToolNames.APPEND, MemoryToolNames.STR_REPLACE)
    private val namespaces = listOf("assistant", "other-assistant", MemoryRepository.GLOBAL_MEMORY_ID)

    private suspend fun call(
        name: String,
        selectedTools: Map<String, Tool> = tools,
        args: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val output = selectedTools.getValue(name).execute(buildJsonObject(args))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    private fun JsonObjectBuilder.editArguments(tool: String, path: String, version: String) {
        put("path", path)
        put("if_version", version)
        when (tool) {
            MemoryToolNames.WRITE -> put("content", "changed\n")
            MemoryToolNames.APPEND -> put("content", "added")
            MemoryToolNames.STR_REPLACE -> {
                put("old_str", "original")
                put("new_str", "changed")
            }
        }
    }

    private suspend fun snapshot(selectedNamespaces: List<String> = namespaces): List<Any?> = buildList {
        for (namespace in selectedNamespaces) {
            val files = repository.getFiles(namespace)
            add(files)
            for (file in files) {
                add(repository.getMetadata(namespace, file.path))
                add(repository.getSources(namespace, file.path))
                add(dao.getChunks(namespace, file.path))
            }
            add(dao.getIndexJobs(namespace))
        }
    }

    @Test
    fun `source refs schema is required only for three contextual content writes`() {
        val contextual = buildMemoryTools(repository, "assistant", writeContext = { MemoryWriteContext() })
            .associateBy { it.name }

        assertEquals(6, contextual.size)
        contextual.forEach { (name, tool) ->
            val schema = tool.parameters() as InputSchema.Obj
            if (name in contentWrites) {
                assertTrue(name, schema.required.orEmpty().contains("source_refs"))
                val refs = schema.properties.getValue("source_refs").jsonObject
                assertEquals("array", refs.string("type"))
                assertEquals("string", refs.getValue("items").jsonObject.string("type"))
                assertEquals("40", refs.string("maxItems"))
            } else {
                assertFalse(name, schema.required.orEmpty().contains("source_refs"))
                assertFalse(name, "source_refs" in schema.properties)
            }
            val ordinary = tools.getValue(name).parameters() as InputSchema.Obj
            assertFalse(name, ordinary.required.orEmpty().contains("source_refs"))
            assertFalse(name, "source_refs" in ordinary.properties)
            assertEquals(
                ordinary.properties.keys + if (name in contentWrites) setOf("source_refs") else emptySet(),
                schema.properties.keys,
            )
        }
    }

    @Test
    fun `contextual writes persist trusted sources in fixed personal namespace despite forged metadata`() = runBlocking<Unit> {
        val source = MemorySource(
            kind = MemorySourceKind.GROUP_CHAT,
            conversationId = "trusted-conversation",
            messageId = "trusted-message",
            groupId = "trusted-group",
            speakerId = "trusted-speaker",
            participantIds = listOf("assistant", "other-assistant"),
            sourceContentHash = "trusted-content-hash",
            occurredAt = 1234L,
        )
        val ref = "${source.conversationId}/${source.messageId}"
        val observed = mutableListOf<List<String>>()
        val contextual = buildMemoryTools(repository, "assistant", writeContext = { refs ->
            observed += refs
            check(refs == listOf(ref))
            MemoryWriteContext(MemoryCategory.ROLE_EXPERIENCE, listOf(source))
        }).associateBy { it.name }

        for (name in contentWrites) {
            for ((index, forgedNamespace) in namespaces.drop(1).withIndex()) {
                val path = "/topics/${name}_$index.md"
                namespaces.drop(1).forEach { repository.writeFile(it, path, "foreign content\n") }
                val foreignBefore = snapshot(namespaces.drop(1))
                if (name != MemoryToolNames.WRITE) repository.writeFile("assistant", path, "original\n")
                val version = repository.getFile("assistant", path)?.version ?: MemoryRepository.NEW_FILE_VERSION
                val result = call(name, contextual) {
                    editArguments(name, path, version)
                    put("source_refs", buildJsonArray { add(ref) })
                    forgeAccess(forgedNamespace, MemoryLifecycle.QUARANTINED)
                    put("assistantId", forgedNamespace)
                    put("category", MemoryCategory.EXTERNAL.name)
                    put("scopeKind", MemoryScopeKind.EXPLICIT_GLOBAL.name)
                    put("groupId", "forged-group")
                    put("speakerId", "forged-speaker")
                    put("sourceKind", MemorySourceKind.MANUAL.name)
                    put("writeContext", buildJsonObject { put("category", MemoryCategory.EXTERNAL.name) })
                }

                assertNull(name, result["error"])
                assertEquals(
                    if (name == MemoryToolNames.APPEND) "original\nadded\n" else "changed\n",
                    repository.getFile("assistant", path)?.content,
                )
                val metadata = repository.getMetadata("assistant", path)!!
                assertEquals("assistant", metadata.assistantId)
                assertEquals(MemoryScopeKind.ASSISTANT.name, metadata.scopeKind)
                assertEquals(MemoryCategory.ROLE_EXPERIENCE.name, metadata.category)
                assertEquals(MemoryLifecycle.ACTIVE.name, metadata.lifecycle)
                val currentSource = repository.getSources("assistant", path)
                    .single { it.status == MemorySourceStatus.CURRENT.name }
                assertEquals("assistant", currentSource.assistantId)
                assertEquals(metadata.revision, currentSource.memoryRevision)
                assertEquals(source.kind.name, currentSource.kind)
                assertEquals(source.conversationId, currentSource.conversationId)
                assertEquals(source.messageId, currentSource.messageId)
                assertEquals(source, Json.decodeFromString<MemorySource>(currentSource.sourceJson))
                assertFalse(repository.getSources("assistant", path).any { it.sourceJson.contains("forged-") })
                assertEquals(foreignBefore, snapshot(namespaces.drop(1)))
            }
        }
        assertEquals(List(6) { listOf(ref) }, observed)
    }

    @Test
    fun `each content write deduplicates source refs before invoking provider`() = runBlocking<Unit> {
        val observed = mutableListOf<List<String>>()
        val contextual = buildMemoryTools(repository, "assistant", writeContext = { refs ->
            observed += refs
            MemoryWriteContext()
        }).associateBy { it.name }
        for (name in contentWrites) {
            val path = "/topics/$name.md"
            repository.writeFile("assistant", path, "original\n")
            val version = repository.getFile("assistant", path)!!.version
            val result = call(name, contextual) {
                editArguments(name, path, version)
                put("source_refs", buildJsonArray {
                    add("conversation/message-b")
                    add("conversation/message-a")
                    add("conversation/message-b")
                    add("conversation/message-a")
                })
            }
            assertNull(name, result["error"])
        }
        assertEquals(List(3) { listOf("conversation/message-b", "conversation/message-a") }, observed)
    }

    @Test
    fun `malformed source refs reject all content writes before provider or data changes`() = runBlocking<Unit> {
        var providerCalls = 0
        val contextual = buildMemoryTools(repository, "assistant", writeContext = {
            providerCalls++
            MemoryWriteContext()
        }).associateBy { it.name }
        val invalid = listOf(
            "scalar" to JsonPrimitive("conversation/message"),
            "null array" to JsonNull,
            "object array" to buildJsonObject {},
            "number item" to buildJsonArray { add(7) },
            "boolean item" to buildJsonArray { add(true) },
            "null item" to buildJsonArray { add(JsonNull) },
            "object item" to buildJsonArray { add(buildJsonObject {}) },
            "array item" to buildJsonArray { add(buildJsonArray {}) },
            "empty item" to buildJsonArray { add("") },
            "blank item" to buildJsonArray { add(" \t\n") },
            "long item" to buildJsonArray { add("x".repeat(101)) },
            "too many items" to buildJsonArray { repeat(41) { add("conversation/message-$it") } },
            "too many duplicates" to buildJsonArray { repeat(41) { add("conversation/message") } },
            "valid then invalid" to buildJsonArray { add("conversation/message"); add(false) },
        )
        val path = "/topics/validation.md"
        namespaces.forEach { repository.writeFile(it, path, "original\n") }
        val version = repository.getFile("assistant", path)!!.version
        val before = snapshot()
        for (name in contentWrites) {
            for ((label, refs) in invalid) {
                val failure = runCatching {
                    call(name, contextual) {
                        editArguments(name, path, version)
                        put("source_refs", refs)
                    }
                }.exceptionOrNull()
                val scenario = "$name $label"
                assertTrue(scenario, failure is IllegalArgumentException || failure is IllegalStateException)
                assertEquals(scenario, 0, providerCalls)
                assertEquals(scenario, before, snapshot())
            }
        }
    }

    @Test
    fun `forty source refs and hundred character refs are accepted without truncation`() = runBlocking<Unit> {
        val refs = List(40) { "conversation/message-$it".padEnd(100, 'x') }
        val observed = mutableListOf<List<String>>()
        val contextual = buildMemoryTools(repository, "assistant", writeContext = {
            observed += it
            MemoryWriteContext()
        }).associateBy { it.name }
        for (name in contentWrites) {
            val path = "/topics/$name.md"
            repository.writeFile("assistant", path, "original\n")
            val version = repository.getFile("assistant", path)!!.version
            val result = call(name, contextual) {
                editArguments(name, path, version)
                put("source_refs", JsonArray(refs.map { JsonPrimitive(it) }))
            }
            assertNull(name, result["error"])
        }
        assertEquals(List(3) { refs }, observed)
    }

    @Test
    fun `provider rejection of missing empty or forged refs leaves all stored state unchanged`() = runBlocking<Unit> {
        val observed = mutableListOf<List<String>>()
        val contextual = buildMemoryTools(repository, "assistant", writeContext = { refs ->
            observed += refs
            check(refs.isNotEmpty()) { "source_refs is required for new memory facts" }
            check(refs.all { it == "trusted-conversation/trusted-message" }) { "Source reference was not shown" }
            MemoryWriteContext()
        }).associateBy { it.name }
        val path = "/topics/provider.md"
        namespaces.forEach { repository.writeFile(it, path, "original\n") }
        val version = repository.getFile("assistant", path)!!.version
        val before = snapshot()
        val rejectedRefs = listOf(
            null,
            emptyList(),
            listOf("forged-conversation/forged-message"),
            listOf("trusted-conversation/trusted-message", "forged-conversation/forged-message"),
        )
        for (name in contentWrites) {
            for (refs in rejectedRefs) {
                val failure = runCatching {
                    call(name, contextual) {
                        editArguments(name, path, version)
                        if (refs != null) put("source_refs", JsonArray(refs.map { JsonPrimitive(it) }))
                        forgeAccess(MemoryRepository.GLOBAL_MEMORY_ID)
                    }
                }.exceptionOrNull()
                assertTrue("$name $refs", failure is IllegalStateException)
                assertEquals(before, snapshot())
            }
        }
        assertEquals(contentWrites.flatMap { rejectedRefs.map { refs -> refs.orEmpty() } }, observed)
    }

    @Test
    fun `before access cancellation propagates for all six tools without provider calls or data changes`() = runBlocking<Unit> {
        val cancellation = CancellationException("access revoked")
        var accessCalls = 0
        var providerCalls = 0
        val contextual = buildMemoryTools(
            repository,
            "assistant",
            writeContext = { providerCalls++; MemoryWriteContext() },
            beforeAccess = { accessCalls++; throw cancellation },
        ).associateBy { it.name }
        val names = listOf(
            MemoryToolNames.READ, MemoryToolNames.LIST, MemoryToolNames.WRITE,
            MemoryToolNames.APPEND, MemoryToolNames.STR_REPLACE, MemoryToolNames.DELETE,
        )
        assertEquals(names.toSet(), contextual.keys)
        val path = "/topics/cancellation.md"
        namespaces.forEach { repository.writeFile(it, path, "original\n") }
        val version = repository.getFile("assistant", path)!!.version
        val before = snapshot()
        names.forEachIndexed { index, name ->
            for (validArgs in listOf(true, false)) {
                val failure = runCatching {
                    call(name, contextual) {
                        if (validArgs) {
                            editArguments(name, path, version)
                            put("path_prefix", "/topics/")
                            put("source_refs", buildJsonArray { add("conversation/message") })
                            forgeAccess(MemoryRepository.GLOBAL_MEMORY_ID)
                        }
                    }
                }.exceptionOrNull()
                assertTrue("$name validArgs=$validArgs", failure === cancellation)
                assertEquals(0, providerCalls)
                assertEquals(before, snapshot())
            }
            assertEquals((index + 1) * 2, accessCalls)
        }
    }

    @Test
    fun `a written file can be read back and edited with the returned version`() = runBlocking {
        val written = call(MemoryToolNames.WRITE) {
            put("path", "/topics/food.md")
            put("content", "- [stated] Likes noodles\n")
            put("if_version", "new")
        }
        val read = call(MemoryToolNames.READ) {
            put("path", buildJsonArray { add("/topics/food.md"); add("/topics/none.md") })
        }
        val file = read.getValue("files").jsonArray.single().jsonObject

        assertEquals(written.string("version"), file.string("version"))
        assertEquals("- [stated] Likes noodles\n", file.string("content"))
        assertEquals(listOf("/topics/none.md"), read.getValue("not_found").jsonArray.map { it.jsonPrimitive.content })

        val appended = call(MemoryToolNames.APPEND) {
            put("path", "/topics/food.md")
            put("content", "- [stated] Allergic to peanuts")
            put("if_version", file.string("version"))
        }
        assertNull(appended["error"])
        assertEquals(
            "- [stated] Likes noodles\n- [stated] Allergic to peanuts\n",
            repository.getFile("assistant", "/topics/food.md")?.content,
        )
    }

    @Test
    fun `memory_read accepts a single path string`() = runBlocking {
        repository.writeFile("assistant", "/profile.md", "- [stated] Name is Lin\n")

        val read = call(MemoryToolNames.READ) { put("path", "/profile.md") }

        assertEquals("/profile.md", read.getValue("files").jsonArray.single().jsonObject.string("path"))
    }

    @Test
    fun `a version conflict returns the latest content so the edit can be retried`() = runBlocking {
        repository.writeFile("assistant", "/topics/food.md", "- [stated] Likes noodles\n")
        val current = repository.getFile("assistant", "/topics/food.md")!!

        val conflict = call(MemoryToolNames.STR_REPLACE) {
            put("path", "/topics/food.md")
            put("old_str", "noodles")
            put("new_str", "rice")
            put("if_version", "stale")
        }

        assertNotNull(conflict["error"])
        assertEquals(current.version, conflict.string("current_version"))
        assertEquals(current.content, conflict.string("current_content"))

        val retried = call(MemoryToolNames.STR_REPLACE) {
            put("path", "/topics/food.md")
            put("old_str", "noodles")
            put("new_str", "rice")
            put("if_version", conflict.string("current_version"))
        }
        assertNull(retried["error"])
        assertEquals("- [stated] Likes rice\n", repository.getFile("assistant", "/topics/food.md")?.content)
    }

    @Test
    fun `editing a missing file points to creating it`() = runBlocking {
        val result = call(MemoryToolNames.APPEND) {
            put("path", "/topics/none.md")
            put("content", "- line")
            put("if_version", "abc")
        }

        assertTrue(result.string("error")!!.contains("does not exist"))
        assertNull(result["current_content"])
    }

    @Test
    fun `card and ID numbers are refused and nothing is written`() = runBlocking {
        val card = call(MemoryToolNames.WRITE) {
            put("path", "/topics/finance.md")
            put("content", "- [stated] Card number is 4111 1111 1111 1111\n")
            put("if_version", "new")
        }
        val id = call(MemoryToolNames.WRITE) {
            put("path", "/profile.md")
            put("content", "- [stated] 身份证号11010519491231002X\n")
            put("if_version", "new")
        }

        assertTrue(card.string("error")!!.contains("bank card number"))
        assertTrue(id.string("error")!!.contains("ID number"))
        assertTrue(repository.getFiles("assistant").isEmpty())
    }

    @Test
    fun `ordinary numbers are not mistaken for card or ID numbers`() {
        assertNull(findForbiddenMemoryContent("Phone is +86 138 0013 8000, order 2026100812345678"))
        assertNull(findForbiddenMemoryContent("Born 1990-05-17, moved in 2021"))
        assertNotNull(findForbiddenMemoryContent("SSN 123-45-6789"))
    }

    private fun JsonObjectBuilder.forgeAccess(
        namespace: String,
        lifecycle: MemoryLifecycle = MemoryLifecycle.ACTIVE,
    ) {
        put("namespace", namespace)
        put("assistant_id", namespace)
        put("lifecycle", lifecycle.name)
        put("activeOnly", false)
        put("active_only", false)
        put("include_inactive", true)
        val source = buildJsonObject {
            put("kind", MemorySourceKind.MANUAL.name)
            put("namespace", namespace)
            put("conversationId", "forged-conversation")
            put("messageId", "forged-message")
        }
        put("source", source)
        put("sources", buildJsonArray { add(source) })
    }

    @Test
    fun `read and list hide inactive files even with forged access arguments`() = runBlocking {
        val activePath = "/topics/active.md"
        repository.writeFile("assistant", activePath, "active content")
        val inactivePaths = MemoryLifecycle.entries.filter { it != MemoryLifecycle.ACTIVE }.map { lifecycle ->
            val path = "/topics/${lifecycle.name.lowercase()}.md"
            repository.writeFile("assistant", path, "hidden ${lifecycle.name} content")
            assertTrue(repository.setLifecycle("assistant", path, lifecycle))
            path
        }
        val namespaces = listOf("other-assistant", MemoryRepository.GLOBAL_MEMORY_ID)
        namespaces.forEach { namespace ->
            repository.writeFile(namespace, "/topics/foreign.md", "foreign content")
        }

        for (namespace in listOf("") + namespaces) {
            val read = call(MemoryToolNames.READ) {
                put("path", buildJsonArray {
                    add(activePath)
                    inactivePaths.forEach { add(it) }
                    add("/topics/foreign.md")
                })
                if (namespace.isNotEmpty()) forgeAccess(namespace)
            }
            val files = read.getValue("files").jsonArray
            assertEquals(listOf(activePath), files.map { it.jsonObject.string("path") })
            assertEquals("active content", files.single().jsonObject.string("content"))
            assertEquals(
                (inactivePaths + "/topics/foreign.md").toSet(),
                read.getValue("not_found").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertFalse(read.toString().contains("hidden"))
            assertFalse(read.toString().contains("foreign content"))

            for (prefix in listOf("", "/topics/") + inactivePaths) {
                val listed = call(MemoryToolNames.LIST) {
                    put("path_prefix", prefix)
                    if (namespace.isNotEmpty()) forgeAccess(namespace)
                }
                assertEquals(
                    if (prefix in inactivePaths) emptyList<String>() else listOf(activePath),
                    listed.getValue("files").jsonArray.map { it.jsonObject.string("path") },
                )
                inactivePaths.forEach { assertFalse(listed.toString().contains(it)) }
                assertFalse(listed.toString().contains("/topics/foreign.md"))
                assertFalse(listed.toString().contains("hidden"))
            }
        }

        inactivePaths.forEach { path ->
            val read = call(MemoryToolNames.READ) { put("path", path) }
            assertTrue(read.getValue("files").jsonArray.isEmpty())
            assertEquals(listOf(path), read.getValue("not_found").jsonArray.map { it.jsonPrimitive.content })
            assertNotNull(repository.getFile("assistant", path))
        }
    }

    @Test
    fun `all mutations reject inactive files without leaking content or changing state`() = runBlocking {
        for (lifecycle in MemoryLifecycle.entries.filter { it != MemoryLifecycle.ACTIVE }) {
            val path = "/topics/${lifecycle.name.lowercase()}.md"
            repository.writeFile("assistant", path, "hidden original ${lifecycle.name} content\n")
            assertTrue(repository.setLifecycle("assistant", path, lifecycle))
            val file = repository.getFile("assistant", path)!!
            val metadata = repository.getMetadata("assistant", path)!!
            val sources = repository.getSources("assistant", path)
            val chunks = dao.getChunks("assistant", path)
            val jobs = dao.getIndexJobs("assistant")

            for (tool in MemoryToolNames.WRITES) {
                for (version in listOf(file.version, "stale", MemoryRepository.NEW_FILE_VERSION)) {
                    for (forged in listOf(false, true)) {
                        val result = call(tool) {
                            put("path", path)
                            put("if_version", version)
                            when (tool) {
                                MemoryToolNames.WRITE, MemoryToolNames.APPEND -> put("content", "changed content")
                                MemoryToolNames.STR_REPLACE -> {
                                    put("old_str", "original")
                                    put("new_str", "changed")
                                }
                            }
                            if (forged) forgeAccess(MemoryRepository.GLOBAL_MEMORY_ID)
                        }
                        val scenario = "$tool $lifecycle $version forged=$forged"
                        assertTrue(scenario, result.string("error")?.contains("inactive") == true)
                        assertNull(scenario, result["current_content"])
                        assertNull(scenario, result["current_version"])
                        assertNull(scenario, result["deleted"])
                        assertFalse(scenario, result.toString().contains(file.content.trim()))
                        assertEquals(scenario, file, repository.getFile("assistant", path))
                        assertEquals(scenario, metadata, repository.getMetadata("assistant", path))
                        assertEquals(scenario, sources, repository.getSources("assistant", path))
                        assertEquals(scenario, chunks, dao.getChunks("assistant", path))
                        assertEquals(scenario, jobs, dao.getIndexJobs("assistant"))
                        assertNull(repository.getFile(MemoryRepository.GLOBAL_MEMORY_ID, path))
                    }
                }
            }
        }
    }

    @Test
    fun `active tools work while forged namespace lifecycle and source cannot change authority`() = runBlocking {
        for (namespace in listOf("other-assistant", MemoryRepository.GLOBAL_MEMORY_ID)) {
            val path = "/topics/normal.md"
            repository.writeFile(namespace, path, "foreign content")
            val foreignFile = repository.getFile(namespace, path)
            val foreignMetadata = repository.getMetadata(namespace, path)
            val foreignSources = repository.getSources(namespace, path)

            val written = call(MemoryToolNames.WRITE) {
                put("path", path)
                put("content", "original\n")
                put("if_version", MemoryRepository.NEW_FILE_VERSION)
                forgeAccess(namespace, MemoryLifecycle.ARCHIVED)
            }
            assertNull(written["error"])
            assertEquals("original\n", repository.getFile("assistant", path)?.content)
            assertEquals(MemoryLifecycle.ACTIVE.name, repository.getMetadata("assistant", path)?.lifecycle)
            assertEquals(MemoryCategory.UNKNOWN.name, repository.getMetadata("assistant", path)?.category)

            val read = call(MemoryToolNames.READ) {
                put("path", path)
                forgeAccess(namespace)
            }.getValue("files").jsonArray.single().jsonObject
            assertEquals("original\n", read.string("content"))
            assertEquals(written.string("version"), read.string("version"))
            val listed = call(MemoryToolNames.LIST) { forgeAccess(namespace) }
            assertEquals(listOf(path), listed.getValue("files").jsonArray.map { it.jsonObject.string("path") })

            val appended = call(MemoryToolNames.APPEND) {
                put("path", path)
                put("content", "added")
                put("if_version", read.string("version"))
                forgeAccess(namespace, MemoryLifecycle.ARCHIVED)
            }
            assertNull(appended["error"])
            assertEquals("original\nadded\n", repository.getFile("assistant", path)?.content)
            val replaced = call(MemoryToolNames.STR_REPLACE) {
                put("path", path)
                put("old_str", "original")
                put("new_str", "edited")
                put("if_version", appended.string("version"))
                forgeAccess(namespace, MemoryLifecycle.ARCHIVED)
            }
            assertNull(replaced["error"])
            assertEquals("edited\nadded\n", repository.getFile("assistant", path)?.content)
            assertEquals(MemoryLifecycle.ACTIVE.name, repository.getMetadata("assistant", path)?.lifecycle)
            val sources = repository.getSources("assistant", path)
            assertTrue(sources.isNotEmpty())
            assertTrue(sources.all { it.kind == MemorySourceKind.UNKNOWN.name })
            assertTrue(sources.all { it.conversationId == null && it.messageId == null })
            assertFalse(sources.any { it.sourceJson.contains("forged-") })

            val overwritten = call(MemoryToolNames.WRITE) {
                put("path", path)
                put("content", "overwritten\n")
                put("if_version", replaced.string("version"))
                forgeAccess(namespace, MemoryLifecycle.ARCHIVED)
            }
            assertNull(overwritten["error"])
            assertEquals("overwritten\n", repository.getFile("assistant", path)?.content)
            assertEquals(MemoryLifecycle.ACTIVE.name, repository.getMetadata("assistant", path)?.lifecycle)
            assertTrue(repository.getSources("assistant", path).all { it.kind == MemorySourceKind.UNKNOWN.name })

            val deleted = call(MemoryToolNames.DELETE) {
                put("path", path)
                put("if_version", overwritten.string("version"))
                forgeAccess(namespace)
            }
            assertNull(deleted["error"])
            assertEquals("true", deleted.string("deleted"))
            assertNull(repository.getFile("assistant", path))
            assertTrue(call(MemoryToolNames.LIST) {}.getValue("files").jsonArray.isEmpty())
            assertEquals(foreignFile, repository.getFile(namespace, path))
            assertEquals(foreignMetadata, repository.getMetadata(namespace, path))
            assertEquals(foreignSources, repository.getSources(namespace, path))
        }
    }

    @Test
    fun `memory_delete removes the file and memory_list reflects it`() = runBlocking {
        repository.writeFile("assistant", "/people/mom.md", "content")
        repository.writeFile("assistant", "/topics/food.md", "content")
        val version = repository.getFile("assistant", "/people/mom.md")!!.version

        val people = call(MemoryToolNames.LIST) { put("path_prefix", "/people/") }
        assertEquals(listOf("/people/mom.md"), people.getValue("files").jsonArray.map { it.jsonObject.string("path") })

        val deleted = call(MemoryToolNames.DELETE) {
            put("path", "/people/mom.md")
            put("if_version", version)
        }
        assertEquals("true", deleted.string("deleted"))
        assertFalse(call(MemoryToolNames.LIST) {}.toString().contains("/people/mom.md"))
    }
}
