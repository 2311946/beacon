package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.MemoryReviewGuard
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReviewGuardTest {
    private val first = "- [stated] Likes noodles"
    private val second = "- [stated] Works on Beacon"
    private val newFact = "- [stated] Lives in Shanghai"
    private val contentWrites = listOf(MemoryToolNames.WRITE, MemoryToolNames.APPEND, MemoryToolNames.STR_REPLACE)

    private fun file(path: String, content: String) = MemoryFile(path, content, 0L)

    private fun guard() = MemoryReviewGuard(listOf(
        file("/topics/food.md", "---\nname: food\ntitle: Food\ndescription: Food preferences\naliases: [meals]\n---\n\n$first\n$first\n"),
        file("/areas/beacon.md", "$second\n"),
    ))

    @Test
    fun `tidy permits full line moves reorder exact deduplication and existing metadata`() {
        val guard = guard()
        for (candidate in listOf(
            "$second\n$first\n",
            "$first\n",
            "\n$first\n\n$second\n",
            "---\nname: food\ntitle: Food\ndescription: Food preferences\naliases: [meals]\n---\n\n$second\n$first\n",
            "---\ntitle: Food\n---\n",
            "---\n---\n$first\n",
            "",
        )) {
            guard.validateWrite(" topics/combined.md ", candidate)
        }
    }

    @Test
    fun `tidy rejects new sentences rewritten facts concatenation fragments and whitespace changes`() {
        val guard = guard()
        for (candidate in listOf(
            newFact,
            "$first\n$newFact\n",
            "- [stated] Likes rice",
            "$first $second",
            "- [stated] Likes noodles and works on Beacon",
            "Likes noodles",
            "  $first",
            "$first ",
            "- [stated] ${first.substringAfter("[stated] ")} ${second.substringAfter("[stated] ")}",
        )) {
            assertTrue(candidate, runCatching { guard.validateWrite("/topics/tidy.md", candidate) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun `existing frontmatter may only be reused as exact complete lines`() {
        val guard = guard()
        for (candidate in listOf("title: Food", "description: Food preferences", "aliases: [meals]", "name: food")) {
            guard.validateWrite("/profile.md", candidate)
            assertTrue(candidate, runCatching { guard.validateWrite("/profile.md", candidate.substringAfter(": ")) }.isFailure)
        }
    }

    @Test
    fun `arbitrary frontmatter delimiters cannot hide new body facts`() {
        val guard = guard()
        for (candidate in listOf(
            "---\n$newFact\n---\n$first\n",
            "---\nA new sentence\n---\n$first\n",
            "---not-a-delimiter\ntitle: Notes\n---\n$newFact\n",
            "---\ntitle: Notes\n---\n$first\n---\n$newFact\n---\n",
            "$first\n---\n$newFact\n---\n",
            "---\ntitle: Notes\n$newFact\n---\n$first\n",
            "---\ntitle: Notes\n$newFact\n",
        )) {
            assertTrue(candidate, runCatching { guard.validateWrite("/profile.md", candidate) }.isFailure)
        }
    }

    @Test
    fun `tidy rejects new facts in every metadata field including name and unknown keys`() {
        val guard = guard()
        for (metadata in listOf(
            "title: Lives in Shanghai",
            "description: Lives in Shanghai",
            "aliases: [Shanghai resident]",
            "name: Shanghai resident",
            "location: Shanghai",
            "title: Fresh title",
            "description: Fresh description",
        )) {
            assertTrue(metadata, runCatching {
                guard.validateWrite("/profile.md", "---\n$metadata\n---\n$first\n")
            }.isFailure)
        }
        assertTrue(runCatching { guard.validateWrite("/profile.md", "title: Fresh title") }.isFailure)
        assertTrue(runCatching { guard.validateWrite("/profile.md", "Fresh description") }.isFailure)
    }

    @Test
    fun `snapshot is frozen and invalid paths are rejected`() {
        val snapshot = mutableListOf(file("/profile.md", "$first\n"))
        val guard = MemoryReviewGuard(snapshot)
        snapshot += file("/profile.md", "$newFact\n")
        assertTrue(runCatching { guard.validateWrite("/profile.md", newFact) }.isFailure)
        assertTrue(runCatching { guard.validateWrite("/../profile.md", first) }.exceptionOrNull() is IllegalArgumentException)
        guard.validateWrite("profile.md", first)
    }

    @Test
    fun `three content tools validate normalized path and exact complete candidate before writing`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/topics/tidy.md"
            val original = "---\ntitle: Notes\n---\n\n$first\n"
            repository.writeFile("assistant", path, original)
            val version = repository.getFile("assistant", path)!!.version
            val observed = mutableListOf<Pair<String, String>>()
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { validatedPath, candidate, refs ->
                assertEquals(original, repository.getFile("assistant", path)?.content)
                assertTrue(refs.isEmpty())
                observed += validatedPath to candidate
            }).associateBy { it.name }
            val expected = when (name) {
                MemoryToolNames.WRITE -> "$second\n$first\n"
                MemoryToolNames.APPEND -> "$original$second\n"
                else -> original.replace("noodles", "rice")
            }
            val result = call(tools, name) {
                put("path", " topics/tidy.md ")
                put("if_version", version)
                when (name) {
                    MemoryToolNames.WRITE -> put("content", expected)
                    MemoryToolNames.APPEND -> put("content", "$second\r\n\n")
                    else -> { put("old_str", "noodles"); put("new_str", "rice") }
                }
            }
            assertNull(result["error"])
            assertEquals(listOf(path to expected), observed)
            assertEquals(expected, repository.getFile("assistant", path)?.content)
        }
    }

    @Test
    fun `append candidate matches repository separator and trailing newline normalization`() = runBlocking<Unit> {
        for (original in listOf("", first, "$first\n", "$first\r\n")) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, original)
            val observed = mutableListOf<String>()
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, candidate, _ -> observed += candidate })
                .associateBy { it.name }
            val version = repository.getFile("assistant", path)!!.version
            val result = call(tools, MemoryToolNames.APPEND) {
                put("path", path)
                put("if_version", version)
                put("content", "$second\r\n\n")
            }
            assertNull(result["error"])
            assertEquals(repository.getFile("assistant", path)?.content, observed.single())
            assertTrue(observed.single().endsWith("$second\n"))
        }
    }

    @Test
    fun `replace deletion candidate matches repository whole line handling`() = runBlocking<Unit> {
        for ((original, oldStr) in listOf(
            "$first\n$second\n" to first,
            "$first\n$second" to second,
            "$first\n$second\n" to second,
            "$first\n$second\n" to "$first\n$second",
            first to first,
            "$first\n" to first,
            "$first\n$second\n" to "noodles",
        )) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, original)
            val observed = mutableListOf<String>()
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, candidate, _ -> observed += candidate })
                .associateBy { it.name }
            val version = repository.getFile("assistant", path)!!.version
            val result = call(tools, MemoryToolNames.STR_REPLACE) {
                put("path", path)
                put("if_version", version)
                put("old_str", oldStr)
                put("new_str", "")
            }
            assertNull(result["error"])
            assertEquals(repository.getFile("assistant", path)?.content, observed.single())
        }
    }

    @Test
    fun `invalid replacements retain repository errors without validating a fabricated candidate`() = runBlocking<Unit> {
        val repository = MemoryRepository(FakeMemoryDAO())
        val path = "/profile.md"
        val original = "$first\n$first\n"
        repository.writeFile("assistant", path, original)
        var validations = 0
        val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, _, _ -> validations++ })
            .associateBy { it.name }
        for ((oldStr, message) in listOf("" to "must not be empty", "missing" to "was not found", first to "more than one place")) {
            val version = repository.getFile("assistant", path)!!.version
            val result = call(tools, MemoryToolNames.STR_REPLACE) {
                put("path", path)
                put("if_version", version)
                put("old_str", oldStr)
                put("new_str", newFact)
            }
            assertTrue(result["error"]?.jsonPrimitive?.content?.contains(message) == true)
            assertEquals(0, validations)
            assertEquals(original, repository.getFile("assistant", path)?.content)
        }
    }

    @Test
    fun `guard rejection for every content tool leaves stored file metadata and sources unchanged`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val dao = FakeMemoryDAO()
            val repository = MemoryRepository(dao)
            val path = "/profile.md"
            repository.writeFile("assistant", path, "$first\n")
            val original = repository.getFile("assistant", path)!!
            val metadata = repository.getMetadata("assistant", path)
            val sources = repository.getSources("assistant", path)
            val jobs = dao.getIndexJobs("assistant")
            val guard = MemoryReviewGuard(repository.getActiveFiles("assistant"))
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { candidatePath, candidate, refs ->
                if (refs.isEmpty()) guard.validateWrite(candidatePath, candidate)
            }).associateBy { it.name }
            val failure = runCatching {
                call(tools, name) {
                    put("path", path)
                    put("if_version", original.version)
                    when (name) {
                        MemoryToolNames.WRITE -> put("content", "$first\n$newFact\n")
                        MemoryToolNames.APPEND -> put("content", newFact)
                        else -> { put("old_str", "noodles"); put("new_str", "rice") }
                    }
                }
            }.exceptionOrNull()
            assertTrue(name, failure is IllegalStateException)
            assertEquals(original, repository.getFile("assistant", path))
            assertEquals(metadata, repository.getMetadata("assistant", path))
            assertEquals(sources, repository.getSources("assistant", path))
            assertEquals(jobs, dao.getIndexJobs("assistant"))
        }
    }

    @Test
    fun `callback cancellation propagates before any content write`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, "$first\n")
            val original = repository.getFile("assistant", path)!!
            val cancellation = CancellationException("校验已取消")
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, _, _ -> throw cancellation })
                .associateBy { it.name }
            val failure = runCatching {
                call(tools, name) {
                    put("path", path)
                    put("if_version", original.version)
                    when (name) {
                        MemoryToolNames.WRITE, MemoryToolNames.APPEND -> put("content", second)
                        else -> { put("old_str", first); put("new_str", second) }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure === cancellation)
            assertEquals(original, repository.getFile("assistant", path))
        }
    }

    @Test
    fun `repository still checks versions after full candidate validation`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, "$first\n")
            val original = repository.getFile("assistant", path)!!
            val concurrent = "$second\n"
            var validations = 0
            val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, _, _ ->
                validations++
                repository.writeFile("assistant", path, concurrent)
            }).associateBy { it.name }
            val result = call(tools, name) {
                put("path", path)
                put("if_version", original.version)
                when (name) {
                    MemoryToolNames.WRITE, MemoryToolNames.APPEND -> put("content", first)
                    else -> { put("old_str", "noodles"); put("new_str", "rice") }
                }
            }
            assertNotNull(result["error"])
            assertEquals(concurrent, result["current_content"]?.jsonPrimitive?.content)
            assertEquals(concurrent, repository.getFile("assistant", path)?.content)
            assertEquals(1, validations)
        }
    }

    @Test
    fun `partial writes do not read inactive content for validation`() = runBlocking<Unit> {
        val repository = MemoryRepository(FakeMemoryDAO())
        val path = "/profile.md"
        repository.writeFile("assistant", path, "$first\n")
        val original = repository.getFile("assistant", path)!!
        repository.setLifecycle("assistant", path, MemoryLifecycle.QUARANTINED)
        var validations = 0
        val tools = buildMemoryTools(repository, "assistant", validateWrite = { _, _, _ -> validations++ })
            .associateBy { it.name }
        for (name in listOf(MemoryToolNames.APPEND, MemoryToolNames.STR_REPLACE)) {
            val result = call(tools, name) {
                put("path", path)
                put("if_version", original.version)
                if (name == MemoryToolNames.APPEND) put("content", second) else {
                    put("old_str", first)
                    put("new_str", second)
                }
            }
            assertTrue(result["error"]?.jsonPrimitive?.content?.contains("inactive") == true)
            assertFalse("current_content" in result)
            assertEquals(0, validations)
            assertEquals(original, repository.getFile("assistant", path))
        }
    }

    @Test
    fun `source context resolves before complete candidate validation`() = runBlocking<Unit> {
        val repository = MemoryRepository(FakeMemoryDAO())
        val events = mutableListOf<String>()
        val tools = buildMemoryTools(
            repository,
            "assistant",
            writeContext = { events += "context"; MemoryWriteContext() },
            beforeAccess = { events += "access" },
            validateWrite = { _, _, _ -> events += "validate" },
        ).associateBy { it.name }
        val result = call(tools, MemoryToolNames.WRITE) {
            put("path", "/profile.md")
            put("if_version", MemoryRepository.NEW_FILE_VERSION)
            put("content", first)
        }
        assertNull(result["error"])
        assertEquals(listOf("access", "context", "validate"), events)
    }

    @Test
    fun `nonempty refs reach context and callback for every content tool without tidy restriction`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, "$first\n")
            val guard = MemoryReviewGuard(repository.getActiveFiles("assistant"))
            val refs = listOf("conversation/message")
            val events = mutableListOf<String>()
            var resolvedRefs: List<String>? = null
            val tools = buildMemoryTools(
                repository,
                "assistant",
                writeContext = { received ->
                    assertEquals(refs, received)
                    resolvedRefs = received
                    events += "context"
                    MemoryWriteContext()
                },
                validateWrite = { candidatePath, candidate, received ->
                    assertEquals(refs, received)
                    assertSame(resolvedRefs, received)
                    events += "validate"
                    if (received.isEmpty()) guard.validateWrite(candidatePath, candidate)
                },
            ).associateBy { it.name }
            val version = repository.getFile("assistant", path)!!.version
            val result = call(tools, name) {
                put("path", path)
                put("if_version", version)
                put("source_refs", buildJsonArray { add(refs.single()); add(refs.single()) })
                when (name) {
                    MemoryToolNames.WRITE, MemoryToolNames.APPEND -> put("content", newFact)
                    else -> { put("old_str", first); put("new_str", newFact) }
                }
            }
            assertNull(result["error"])
            assertEquals(listOf("context", "validate"), events)
            assertTrue(repository.getFile("assistant", path)!!.content.contains(newFact))
        }
    }

    @Test
    fun `invalid nonempty refs fail context before validation and leave content unchanged`() = runBlocking<Unit> {
        for (name in contentWrites) {
            val repository = MemoryRepository(FakeMemoryDAO())
            val path = "/profile.md"
            repository.writeFile("assistant", path, "$first\n")
            val original = repository.getFile("assistant", path)!!
            var validations = 0
            val tools = buildMemoryTools(
                repository,
                "assistant",
                writeContext = { error("引用未通过 contextFor 检查") },
                validateWrite = { _, _, _ -> validations++ },
            ).associateBy { it.name }
            assertTrue(runCatching {
                call(tools, name) {
                    put("path", path)
                    put("if_version", original.version)
                    put("source_refs", buildJsonArray { add("untrusted/message") })
                    when (name) {
                        MemoryToolNames.WRITE, MemoryToolNames.APPEND -> put("content", first)
                        else -> { put("old_str", first); put("new_str", second) }
                    }
                }
            }.isFailure)
            assertEquals(0, validations)
            assertEquals(original, repository.getFile("assistant", path))
        }
    }

    @Test
    fun `interleaved writes preserve refs belonging to each invocation`() = runBlocking<Unit> {
        val repository = MemoryRepository(FakeMemoryDAO())
        val firstContextEntered = CompletableDeferred<Unit>()
        val secondValidated = CompletableDeferred<Unit>()
        val observed = mutableMapOf<String, List<String>>()
        val tools = buildMemoryTools(
            repository,
            "assistant",
            writeContext = { refs ->
                if (refs.isEmpty()) {
                    firstContextEntered.complete(Unit)
                    secondValidated.await()
                }
                MemoryWriteContext()
            },
            validateWrite = { path, _, refs ->
                observed[path] = refs
                if (refs.isNotEmpty()) secondValidated.complete(Unit)
            },
        ).associateBy { it.name }
        val firstWrite = async {
            call(tools, MemoryToolNames.WRITE) {
                put("path", "/first.md")
                put("if_version", MemoryRepository.NEW_FILE_VERSION)
                put("content", first)
                put("source_refs", buildJsonArray {})
            }
        }
        firstContextEntered.await()
        val secondResult = call(tools, MemoryToolNames.WRITE) {
            put("path", "/second.md")
            put("if_version", MemoryRepository.NEW_FILE_VERSION)
            put("content", second)
            put("source_refs", buildJsonArray { add("conversation/message") })
        }
        assertNull(secondResult["error"])
        assertNull(firstWrite.await()["error"])
        assertEquals(emptyList<String>(), observed["/first.md"])
        assertEquals(listOf("conversation/message"), observed["/second.md"])
    }

    private suspend fun call(tools: Map<String, Tool>, name: String, args: JsonObjectBuilder.() -> Unit): JsonObject {
        val output = tools.getValue(name).execute(buildJsonObject(args))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }
}
