package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.model.MemoryCategory
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.MemoryScopeKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.MemorySourceStatus
import me.rerere.rikkahub.data.model.MemoryWriteContext
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryProvenanceTest {
    private val dao = FakeMemoryDAO()
    private val repository = MemoryRepository(dao)
    private val assistant = "assistant-a"
    private val other = "assistant-b"
    private val path = "/topics/tea.md"
    private val body = "中文记忆：喜欢喝茶。\n"

    private suspend fun create(
        namespace: String = assistant,
        path: String = this.path,
        content: String = body,
        context: MemoryWriteContext? = null,
    ): MemoryFile = (repository.writeFile(
        namespace, path, content, MemoryRepository.NEW_FILE_VERSION, context,
    ) as MemoryWriteResult.Success).file!!

    private suspend fun job(namespace: String = assistant, path: String = this.path): MemoryIndexJobEntity =
        dao.getIndexJobs(namespace).single { it.path == path }

    private fun chunk(job: MemoryIndexJobEntity, index: Int = 0) = MemoryChunkEntity(
        assistantId = job.assistantId,
        path = job.path,
        chunkIndex = index,
        memoryRevision = job.memoryRevision,
        contentHash = job.contentHash,
        chunkerVersion = 1,
        content = "索引片段$index",
    )

    private data class State(
        val file: MemoryFile?,
        val metadata: MemoryMetadataEntity?,
        val sources: List<MemorySourceEntity>,
        val chunks: List<MemoryChunkEntity>,
        val jobs: List<MemoryIndexJobEntity>,
    )

    private suspend fun state(namespace: String = assistant, path: String = this.path) = State(
        repository.getFile(namespace, path),
        repository.getMetadata(namespace, path),
        repository.getSources(namespace, path),
        dao.getChunks(namespace, path),
        dao.getIndexJobs(namespace).filter { it.path == path },
    )

    private inline fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("应拒绝不完整或不一致的数据")
        } catch (_: IllegalArgumentException) {
            // 校验失败不得被当作合法来源或索引结果接受。
        }
    }

    @Test
    fun `全局作用域必须显式选择且助手命名空间互不授权`() {
        val global = MemoryScope.of(MemoryRepository.GLOBAL_MEMORY_ID)
        val local = MemoryScope.of(assistant)
        assertEquals(MemoryScopeKind.EXPLICIT_GLOBAL, global.kind)
        assertEquals(MemoryScopeKind.ASSISTANT, local.kind)
        assertTrue(global.allowsNamespace(MemoryRepository.GLOBAL_MEMORY_ID))
        assertTrue(local.allowsNamespace(assistant))
        assertFalse(global.allowsNamespace(assistant))
        assertFalse(local.allowsNamespace(MemoryRepository.GLOBAL_MEMORY_ID))
        assertFalse(local.allowsNamespace(other))
    }

    @Test
    fun `空作用域及伪装成助手的全局作用域均被拒绝`() {
        listOf("", " ", "\n").forEach { namespace ->
            assertInvalid { MemoryScope.of(namespace) }
        }
        assertInvalid { MemoryScope(MemoryScope.GLOBAL_NAMESPACE, MemoryScopeKind.ASSISTANT) }
        assertInvalid { MemoryScope(assistant, MemoryScopeKind.EXPLICIT_GLOBAL) }
    }

    @Test
    fun `来源校验拒绝缺少必要引用且不推测会话群组或外部来源`() {
        val invalid = listOf(
            MemorySource(MemorySourceKind.UNKNOWN, messageId = "message"),
            MemorySource(MemorySourceKind.MANUAL, conversationId = " ", messageId = "message"),
            MemorySource(MemorySourceKind.PRIVATE_CHAT),
            MemorySource(MemorySourceKind.GROUP_CHAT, conversationId = "conversation"),
            MemorySource(MemorySourceKind.GROUP_CHAT, groupId = "group"),
            MemorySource(MemorySourceKind.EXTERNAL_IMPORT, externalSystem = "notes"),
            MemorySource(MemorySourceKind.EXTERNAL_IMPORT, externalId = "note"),
            MemorySource(MemorySourceKind.COPY, copiedFromNamespace = assistant),
            MemorySource(MemorySourceKind.COPY, copiedFromPath = path),
            MemorySource(MemorySourceKind.UNKNOWN, participantIds = listOf("speaker", " ")),
        )
        invalid.forEach { source -> assertInvalid { source.validate() } }
        assertInvalid { MemoryWriteContext(sources = invalid).validate() }
    }

    @Test
    fun `合法最小来源不补齐未知发言者参与者或时间`() {
        val sources = listOf(
            MemorySource(MemorySourceKind.UNKNOWN),
            MemorySource(MemorySourceKind.MANUAL),
            MemorySource(MemorySourceKind.PRIVATE_CHAT, conversationId = "conversation"),
            MemorySource(MemorySourceKind.GROUP_CHAT, conversationId = "conversation", groupId = "group"),
            MemorySource(MemorySourceKind.EXTERNAL_IMPORT, externalSystem = "notes", externalId = "note"),
            MemorySource(MemorySourceKind.COPY, copiedFromNamespace = assistant, copiedFromPath = path),
        )
        MemoryWriteContext(sources = sources).validate()
        sources.forEach { source ->
            assertNull(source.messageId)
            assertNull(source.speakerId)
            assertNull(source.occurredAt)
            assertNull(source.sourceContentHash)
            assertTrue(source.participantIds.isEmpty())
        }
    }

    @Test
    fun `中文正文使用完整UTF8 SHA256而非界面短版本`() {
        val hash = memoryContentHash(body)
        assertEquals("9a67ac0ed2323e434c7a5865d8113ec3064da610ff0d3e22157021621d8c6e57", hash)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(hash, MemoryFile(path, body, 0).version)
        assertNotEquals(hash, memoryContentHash(body.trimEnd()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", memoryContentHash(""))
    }

    @Test
    fun `无context创建UNKNOWN元数据来源及匹配索引任务且不猜正文中的来源`() = runBlocking {
        val content = "群group里speaker说自己喜欢茶，conversation和message只是正文。"
        val file = create(content = content)
        val metadata = repository.getMetadata(assistant, path)!!
        val source = repository.getSources(assistant, path).single()
        val decoded = JsonInstant.decodeFromString<MemorySource>(source.sourceJson)
        assertEquals(assistant, metadata.assistantId)
        assertEquals(path, metadata.path)
        assertEquals(MemoryScopeKind.ASSISTANT.name, metadata.scopeKind)
        assertEquals(MemoryCategory.UNKNOWN.name, metadata.category)
        assertEquals(MemoryLifecycle.ACTIVE.name, metadata.lifecycle)
        assertEquals(memoryContentHash(content), metadata.contentHash)
        assertTrue(metadata.revision.isNotBlank())
        assertEquals(file.updatedAt, metadata.updatedAt)
        assertEquals(MemorySource(MemorySourceKind.UNKNOWN), decoded)
        assertEquals(MemorySourceKind.UNKNOWN.name, source.kind)
        assertEquals(MemorySourceStatus.CURRENT.name, source.status)
        assertEquals(metadata.revision, source.memoryRevision)
        assertNull(source.conversationId)
        assertNull(source.messageId)
        assertEquals(MemoryIndexJobEntity(assistant, path, metadata.revision, metadata.contentHash, metadata.updatedAt), job())
        assertTrue(dao.getChunks(assistant, path).isEmpty())
    }

    @Test
    fun `全局和不同助手的同路径正文元数据来源与任务隔离`() = runBlocking {
        val namespaces = listOf(assistant, other, MemoryRepository.GLOBAL_MEMORY_ID)
        namespaces.forEach { create(namespace = it, content = "正文-$it") }
        namespaces.forEach { namespace ->
            assertEquals("正文-$namespace", repository.getFile(namespace, path)?.content)
            assertEquals(namespace, repository.getMetadata(namespace, path)?.assistantId)
            assertEquals(MemoryScope.of(namespace).kind.name, repository.getMetadata(namespace, path)?.scopeKind)
            assertEquals(namespace, repository.getSources(namespace, path).single().assistantId)
            assertEquals(namespace, job(namespace).assistantId)
            assertEquals(listOf(path), repository.getActiveFiles(namespace).map { it.path })
        }
        assertNull(repository.getMetadata("missing", path))
        assertTrue(repository.getSources("missing", path).isEmpty())
    }

    @Test
    fun `context保存完整群聊及外部引用并对重复来源去重`() = runBlocking {
        val group = MemorySource(
            kind = MemorySourceKind.GROUP_CHAT,
            conversationId = "conversation",
            messageId = "message",
            groupId = "group",
            speakerId = "speaker",
            participantIds = listOf("speaker", "listener"),
            sourceContentHash = memoryContentHash("引用正文"),
            occurredAt = 1234L,
        )
        val external = MemorySource(
            MemorySourceKind.EXTERNAL_IMPORT,
            externalSystem = "notes",
            externalId = "note-1",
            externalVersion = "v2",
        )
        create(context = MemoryWriteContext(MemoryCategory.EXTERNAL, listOf(group, group, external)))
        val metadata = repository.getMetadata(assistant, "topics/tea.md")!!
        val sources = repository.getSources(assistant, "topics/tea.md")
        assertEquals(MemoryCategory.EXTERNAL.name, metadata.category)
        assertEquals(setOf(group, external), sources.map { JsonInstant.decodeFromString<MemorySource>(it.sourceJson) }.toSet())
        assertEquals(2, sources.size)
        assertEquals(2, sources.map { it.sourceId }.distinct().size)
        assertTrue(sources.all { it.path == path && it.memoryRevision == metadata.revision && it.status == "CURRENT" })
        assertEquals("conversation", sources.single { it.kind == "GROUP_CHAT" }.conversationId)
        assertEquals("message", sources.single { it.kind == "GROUP_CHAT" }.messageId)
    }

    @Test
    fun `人工编辑只补来源不会把已分类记忆重置为未知`() = runBlocking {
        create(context = MemoryWriteContext(MemoryCategory.USER_FACT,
            listOf(MemorySource(MemorySourceKind.PRIVATE_CHAT, conversationId = "private"))))
        val manual = MemoryWriteContext(sources = listOf(MemorySource(MemorySourceKind.MANUAL)))
        repository.writeFile(assistant, path, "人工补充后的正文", context = manual)
        assertEquals(MemoryCategory.USER_FACT.name, repository.getMetadata(assistant, path)?.category)
        assertEquals("MANUAL", repository.getSources(assistant, path).single { it.status == "CURRENT" }.kind)
        repository.writeFile(assistant, path, "明确重置分类", context = MemoryWriteContext(MemoryCategory.UNKNOWN))
        assertEquals(MemoryCategory.UNKNOWN.name, repository.getMetadata(assistant, path)?.category)
    }

    @Test
    fun `不合法context在新建或编辑前拒绝且不写任何旁表`() = runBlocking {
        val invalid = MemoryWriteContext(sources = listOf(MemorySource(MemorySourceKind.PRIVATE_CHAT)))
        assertInvalid { repository.writeFile(assistant, path, body, context = invalid) }
        assertNull(repository.getFile(assistant, path))
        assertNull(repository.getMetadata(assistant, path))
        assertTrue(repository.getSources(assistant, path).isEmpty())
        assertTrue(dao.getIndexJobs(assistant).isEmpty())
        create()
        val before = state()
        assertInvalid { repository.appendToFile(assistant, path, "追加", null, invalid) }
        assertEquals(before, state())
    }

    @Test
    fun `replace及append保留旧来源为STALE并绑定新revision与hash`() = runBlocking {
        val source = MemorySource(MemorySourceKind.PRIVATE_CHAT, conversationId = "conversation", messageId = "message")
        val first = create(context = MemoryWriteContext(MemoryCategory.USER_FACT, listOf(source)))
        val original = repository.getSources(assistant, path).single()
        val firstRevision = repository.getMetadata(assistant, path)!!.revision
        val replaced = repository.replaceInFile(assistant, path, "喝茶", "喝水", first.version) as MemoryWriteResult.Success
        val second = replaced.file!!
        val secondMetadata = repository.getMetadata(assistant, path)!!
        assertNotEquals(firstRevision, secondMetadata.revision)
        assertEquals(memoryContentHash(second.content), secondMetadata.contentHash)
        assertEquals(MemoryCategory.USER_FACT.name, secondMetadata.category)
        assertEquals(original.copy(status = "STALE"), repository.getSources(assistant, path).single { it.sourceId == original.sourceId })
        val secondSource = repository.getSources(assistant, path).single { it.sourceId != original.sourceId }
        assertEquals("UNKNOWN", secondSource.kind)
        assertEquals(secondMetadata.revision, secondSource.memoryRevision)
        val manual = MemorySource(MemorySourceKind.MANUAL)
        val appended = repository.appendToFile(
            assistant, path, "不加糖", second.version,
            MemoryWriteContext(MemoryCategory.USER_FACT, listOf(manual)),
        ) as MemoryWriteResult.Success
        val latest = repository.getMetadata(assistant, path)!!
        val sources = repository.getSources(assistant, path)
        assertNotEquals(secondMetadata.revision, latest.revision)
        assertEquals(memoryContentHash(appended.file!!.content), latest.contentHash)
        assertEquals(2, sources.count { it.status == "STALE" })
        assertEquals(manual, JsonInstant.decodeFromString<MemorySource>(sources.single { it.status == "CURRENT" }.sourceJson))
        assertEquals(latest.revision, job().memoryRevision)
    }

    @Test
    fun `无context同正文写入不改revision时间来源已发布分块或任务`() = runBlocking {
        val first = create()
        val pending = job()
        assertTrue(dao.publishChunks(pending, listOf(chunk(pending))))
        val before = state()
        assertEquals(MemoryWriteResult.Success(first), repository.writeFile(assistant, path, body, first.version))
        assertEquals(before, state())
        assertEquals(MemoryWriteResult.Success(first), repository.replaceInFile(assistant, path, "喝茶", "喝茶", first.version))
        assertEquals(before, state())
    }

    @Test
    fun `版本冲突和编辑拒绝不改变正文元数据来源分块及任务`() = runBlocking {
        val file = create()
        val pending = job()
        dao.upsertChunks(listOf(chunk(pending)))
        val before = state()
        val context = MemoryWriteContext(MemoryCategory.EXTERNAL, listOf(MemorySource(MemorySourceKind.MANUAL)))
        val conflicts = listOf(
            repository.writeFile(assistant, path, "覆盖", "过期版本", context),
            repository.writeFile(assistant, path, "覆盖", MemoryRepository.NEW_FILE_VERSION, context),
            repository.replaceInFile(assistant, path, "喝茶", "喝水", "过期版本", context),
            repository.appendToFile(assistant, path, "追加", "过期版本", context),
            repository.deleteFile(assistant, path, "过期版本"),
        )
        conflicts.forEach { assertEquals(MemoryWriteResult.Conflict(file), it) }
        assertTrue(repository.replaceInFile(assistant, path, "不存在", "替换", file.version) is MemoryWriteResult.Rejected)
        assertTrue(repository.writeFile(assistant, path, "大".repeat(MemoryFile.MAX_BYTES), file.version) is MemoryWriteResult.Rejected)
        assertEquals(before, state())
    }

    @Test
    fun `copy生成显式COPY来源及目标namespace新revision完整hash而不复制原会话引用`() = runBlocking {
        create(context = MemoryWriteContext(
            MemoryCategory.USER_FACT,
            listOf(MemorySource(MemorySourceKind.PRIVATE_CHAT, conversationId = "private", messageId = "message")),
        ))
        val original = state()
        create(namespace = other, content = "目标旧内容")
        val oldTargetRevision = repository.getMetadata(other, path)!!.revision
        repository.copyFiles(assistant, other)
        val copied = state(other)
        val metadata = copied.metadata!!
        val source = copied.sources.single()
        assertEquals(body, copied.file!!.content)
        assertEquals(other, metadata.assistantId)
        assertEquals(MemoryScopeKind.ASSISTANT.name, metadata.scopeKind)
        assertEquals(MemoryCategory.USER_FACT.name, metadata.category)
        assertEquals(memoryContentHash(body), metadata.contentHash)
        assertNotEquals(original.metadata!!.revision, metadata.revision)
        assertNotEquals(oldTargetRevision, metadata.revision)
        assertEquals(MemorySourceKind.COPY.name, source.kind)
        assertEquals(other, source.assistantId)
        assertEquals(metadata.revision, source.memoryRevision)
        assertNull(source.conversationId)
        assertNull(source.messageId)
        assertEquals(MemorySource(MemorySourceKind.COPY, copiedFromNamespace = assistant, copiedFromPath = path),
            JsonInstant.decodeFromString<MemorySource>(source.sourceJson))
        assertEquals(metadata.revision, job(other).memoryRevision)
        assertEquals(original, state())
        repository.copyFiles(assistant, MemoryRepository.GLOBAL_MEMORY_ID)
        assertEquals(MemoryScopeKind.EXPLICIT_GLOBAL.name, repository.getMetadata(MemoryRepository.GLOBAL_MEMORY_ID, path)?.scopeKind)
    }

    @Test
    fun `copy既不复活停用的源也不复活已有停用目标`() = runBlocking {
        create()
        val inactive = MemoryLifecycle.values().filter { it != MemoryLifecycle.ACTIVE }
        inactive.forEach { lifecycle ->
            repository.setLifecycle(assistant, path, lifecycle)
            val destination = "copy-${lifecycle.name}"
            repository.copyFiles(assistant, destination)
            assertEquals(lifecycle.name, repository.getMetadata(destination, path)?.lifecycle)
            assertNull(repository.getActiveFile(destination, path))
            assertTrue(dao.getIndexJobs(destination).isEmpty())
            repository.setLifecycle(assistant, path, MemoryLifecycle.ACTIVE)
            create(namespace = destination, path = "/other.md")
            repository.copyFiles(assistant, destination)
            assertEquals(lifecycle.name, repository.getMetadata(destination, path)?.lifecycle)
            assertNull(repository.getActiveFile(destination, path))
            assertTrue(dao.getIndexJobs(destination).none { it.path == path })
        }
    }

    @Test
    fun `生命周期改变保留原文时间与来源并清理分块和旧任务`() = runBlocking {
        val file = create()
        val pending = job()
        assertTrue(dao.publishChunks(pending, listOf(chunk(pending))))
        val old = state()
        assertTrue(repository.setLifecycle(assistant, "topics/tea.md", MemoryLifecycle.ARCHIVED))
        val archived = state()
        assertEquals(file, archived.file)
        assertEquals(old.metadata!!.contentHash, archived.metadata!!.contentHash)
        assertNotEquals(old.metadata.revision, archived.metadata.revision)
        assertEquals(old.sources, archived.sources)
        assertTrue(archived.chunks.isEmpty())
        assertTrue(archived.jobs.isEmpty())
        assertTrue(repository.setLifecycle(assistant, path, MemoryLifecycle.ACTIVE))
        assertEquals(file, repository.getActiveFile(assistant, path))
        assertNotEquals(archived.metadata.revision, job().memoryRevision)
        assertFalse(repository.setLifecycle(assistant, "/missing.md", MemoryLifecycle.ARCHIVED))
        assertFalse(repository.setLifecycle(assistant, "../invalid.md", MemoryLifecycle.ARCHIVED))
    }

    @Test
    fun `active读取隔离所有停用生命周期但管理读取保留正文及无元数据旧文件`() = runBlocking {
        create()
        val legacyPath = "/legacy.md"
        dao.upsertFile(MemoryFileEntity(assistant, legacyPath, "迁移旧正文", 1))
        create(namespace = other, content = "其他助手正文")
        MemoryLifecycle.values().filter { it != MemoryLifecycle.ACTIVE }.forEach { lifecycle ->
            repository.setLifecycle(assistant, path, lifecycle)
            assertNull(repository.getActiveFile(assistant, "topics/tea.md"))
            assertEquals(listOf(legacyPath), repository.getActiveFiles(assistant).map { it.path })
            assertEquals(body, repository.getFile(assistant, path)?.content)
            assertEquals(setOf(path, legacyPath), repository.getFiles(assistant).map { it.path }.toSet())
            assertEquals("其他助手正文", repository.getActiveFile(other, path)?.content)
        }
        assertEquals("迁移旧正文", repository.getActiveFile(assistant, legacyPath)?.content)
        assertNull(repository.getActiveFile(assistant, "../invalid.md"))
        assertNull(repository.getMetadata(assistant, "../invalid.md"))
        assertTrue(repository.getSources(assistant, "../invalid.md").isEmpty())
    }

    @Test
    fun `模型activeOnly写替换追加删除停用文件均不泄漏正文且不改变任何状态`() = runBlocking {
        create()
        MemoryLifecycle.values().filter { it != MemoryLifecycle.ACTIVE }.forEach { lifecycle ->
            repository.setLifecycle(assistant, path, lifecycle)
            val before = state()
            listOf(null, "过期版本", MemoryRepository.NEW_FILE_VERSION).forEach { version ->
                val results = listOf(
                    repository.writeFile(assistant, path, "覆盖", version, activeOnly = true),
                    repository.replaceInFile(assistant, path, "喝茶", "喝水", version, activeOnly = true),
                    repository.appendToFile(assistant, path, "追加", version, activeOnly = true),
                    repository.deleteFile(assistant, path, version, activeOnly = true),
                )
                results.forEach { result ->
                    assertTrue(result is MemoryWriteResult.Rejected)
                    assertFalse(result.toString().contains(body.trim()))
                    assertFalse(result is MemoryWriteResult.Conflict)
                }
            }
            assertEquals(before, state())
        }
        val activePath = "/active.md"
        val active = (repository.writeFile(assistant, activePath, "甲", activeOnly = true) as MemoryWriteResult.Success).file!!
        val replaced = (repository.replaceInFile(assistant, activePath, "甲", "乙", active.version, activeOnly = true) as MemoryWriteResult.Success).file!!
        val appended = (repository.appendToFile(assistant, activePath, "丙", replaced.version, activeOnly = true) as MemoryWriteResult.Success).file!!
        assertEquals(MemoryWriteResult.Success(null), repository.deleteFile(assistant, activePath, appended.version, activeOnly = true))
    }

    @Test
    fun `普通人工编辑停用文件不隐式复活或重新排队`() = runBlocking {
        create()
        MemoryLifecycle.values().filter { it != MemoryLifecycle.ACTIVE }.forEach { lifecycle ->
            repository.setLifecycle(assistant, path, lifecycle)
            val oldRevision = repository.getMetadata(assistant, path)!!.revision
            assertTrue(repository.writeFile(assistant, path, "人工正文") is MemoryWriteResult.Success)
            assertTrue(repository.replaceInFile(assistant, path, "人工", "修改", null) is MemoryWriteResult.Success)
            assertTrue(repository.appendToFile(assistant, path, "追加", null) is MemoryWriteResult.Success)
            assertEquals("修改正文\n追加\n", repository.getFile(assistant, path)?.content)
            assertEquals(lifecycle.name, repository.getMetadata(assistant, path)?.lifecycle)
            assertNotEquals(oldRevision, repository.getMetadata(assistant, path)?.revision)
            assertNull(repository.getActiveFile(assistant, path))
            assertTrue(dao.getIndexJobs(assistant).isEmpty())
        }
    }

    @Test
    fun `单文件删除清理元数据来源分块任务且不影响其他路径和namespace`() = runBlocking {
        val file = create()
        create(path = "/keep.md")
        create(namespace = other)
        val pending = job()
        dao.upsertChunks(listOf(chunk(pending)))
        val keep = state(path = "/keep.md")
        val otherState = state(other)
        assertEquals(MemoryWriteResult.Success(null), repository.deleteFile(assistant, path, file.version))
        assertEquals(State(null, null, emptyList(), emptyList(), emptyList()), state())
        assertEquals(keep, state(path = "/keep.md"))
        assertEquals(otherState, state(other))
        assertFalse(dao.publishChunks(pending, listOf(chunk(pending))))
    }

    @Test
    fun `助手批量删除清理所有旁表且保留其他助手与全局库`() = runBlocking {
        listOf(path, "/another.md").forEach { filePath ->
            create(path = filePath)
            val pending = job(path = filePath)
            dao.upsertChunks(listOf(chunk(pending)))
        }
        create(namespace = other)
        create(namespace = MemoryRepository.GLOBAL_MEMORY_ID)
        val otherState = state(other)
        val globalState = state(MemoryRepository.GLOBAL_MEMORY_ID)
        repository.deleteFilesOfAssistant(assistant)
        assertTrue(repository.getFiles(assistant).isEmpty())
        listOf(path, "/another.md").forEach { filePath ->
            assertEquals(State(null, null, emptyList(), emptyList(), emptyList()), state(path = filePath))
        }
        assertEquals(otherState, state(other))
        assertEquals(globalState, state(MemoryRepository.GLOBAL_MEMORY_ID))
    }

    @Test
    fun `相同正文删除重建的新revision阻止ABA旧任务发布`() = runBlocking {
        val original = create()
        val oldJob = job()
        repository.deleteFile(assistant, path)
        val recreated = create()
        val freshJob = job()
        assertEquals(original.version, recreated.version)
        assertEquals(oldJob.contentHash, freshJob.contentHash)
        assertNotEquals(oldJob.memoryRevision, freshJob.memoryRevision)
        val before = state()
        assertFalse(dao.publishChunks(oldJob, listOf(chunk(oldJob))))
        assertEquals(before, state())
        assertTrue(dao.publishChunks(freshJob, listOf(chunk(freshJob))))
    }

    @Test
    fun `编辑或停用前的过期任务不能发布或移除新任务`() = runBlocking {
        create()
        val oldJob = job()
        repository.appendToFile(assistant, path, "新正文", null)
        val afterEdit = state()
        assertFalse(dao.publishChunks(oldJob, listOf(chunk(oldJob))))
        assertEquals(afterEdit, state())
        val beforeArchive = job()
        repository.setLifecycle(assistant, path, MemoryLifecycle.ARCHIVED)
        val archived = state()
        assertFalse(dao.publishChunks(beforeArchive, listOf(chunk(beforeArchive))))
        assertEquals(archived, state())
        repository.setLifecycle(assistant, path, MemoryLifecycle.ACTIVE)
        val reactivated = state()
        assertFalse(dao.publishChunks(beforeArchive, listOf(chunk(beforeArchive))))
        assertEquals(reactivated, state())
    }

    @Test
    fun `空分块结果可发布并清理旧分块与任务但不改原文证据`() = runBlocking {
        create(content = "")
        val pending = job()
        dao.upsertChunks(listOf(chunk(pending)))
        val before = state()
        assertTrue(dao.publishChunks(pending, emptyList()))
        assertTrue(dao.getChunks(assistant, path).isEmpty())
        assertTrue(dao.getIndexJobs(assistant).isEmpty())
        assertEquals(before.file, repository.getFile(assistant, path))
        assertEquals(before.metadata, repository.getMetadata(assistant, path))
        assertEquals(before.sources, repository.getSources(assistant, path))
        assertFalse(dao.publishChunks(pending, emptyList()))
    }

    @Test
    fun `多个连续分块发布替换旧结果并仅消费自己的任务`() = runBlocking {
        create()
        create(namespace = other)
        val pending = job()
        dao.upsertChunks(listOf(chunk(pending, 9)))
        val expected = (0..2).map { chunk(pending, it) }
        val otherState = state(other)
        assertTrue(dao.publishChunks(pending, expected))
        assertEquals(expected, dao.getChunks(assistant, path))
        assertTrue(dao.getIndexJobs(assistant).isEmpty())
        assertEquals(otherState, state(other))
        assertFalse(dao.publishChunks(pending, expected))
        assertEquals(expected, dao.getChunks(assistant, path))
    }

    @Test
    fun `分块索引必须从零连续有序且不允许负数或重复`() = runBlocking {
        create()
        val pending = job()
        dao.upsertChunks(listOf(chunk(pending)))
        val before = state()
        listOf(listOf(-1), listOf(1), listOf(0, 2), listOf(1, 0), listOf(0, 0)).forEach { indices ->
            assertInvalid { dao.publishChunks(pending, indices.map { chunk(pending, it) }) }
            assertEquals(before, state())
        }
    }

    @Test
    fun `分块namespace路径revision和完整hash任一不匹配均拒绝且不改旁表`() = runBlocking {
        create()
        val pending = job()
        val valid = chunk(pending)
        dao.upsertChunks(listOf(valid))
        val before = state()
        val invalid = listOf(
            valid.copy(assistantId = other),
            valid.copy(path = "/other.md"),
            valid.copy(memoryRevision = "过期revision"),
            valid.copy(contentHash = pending.contentHash.take(12)),
            valid.copy(contentHash = memoryContentHash("其他正文")),
        )
        invalid.forEach { result ->
            assertInvalid { dao.publishChunks(pending, listOf(result)) }
            assertEquals(before, state())
        }
    }

    @Test
    fun `分块版本必须为正且一次发布不能混用多个chunker版本`() = runBlocking {
        create()
        val pending = job()
        val before = state()
        listOf(0, -1).forEach { version ->
            assertInvalid { dao.publishChunks(pending, listOf(chunk(pending).copy(chunkerVersion = version))) }
            assertEquals(before, state())
        }
        assertInvalid {
            dao.publishChunks(pending, listOf(chunk(pending), chunk(pending, 1).copy(chunkerVersion = 2)))
        }
        assertEquals(before, state())
    }

    @Test
    fun `发布验证正文hash元数据及真实排队任务而非仅相信调用者job`() = runBlocking {
        create()
        val pending = job()
        val before = state()
        listOf(
            pending.copy(assistantId = other),
            pending.copy(path = "/missing.md"),
            pending.copy(memoryRevision = "过期revision"),
            pending.copy(contentHash = memoryContentHash("伪造正文")),
            pending.copy(queuedAt = pending.queuedAt + 1),
        ).forEach { forged ->
            assertFalse(dao.publishChunks(forged, listOf(chunk(forged))))
            assertEquals(before, state())
        }
        dao.upsertFile(MemoryFileEntity(assistant, path, "绕过repository的正文修改", before.file!!.updatedAt))
        val changed = state()
        assertFalse(dao.publishChunks(pending, listOf(chunk(pending))))
        assertEquals(changed, state())
        dao.upsertFile(MemoryFileEntity(assistant, path, body, before.file.updatedAt))
        dao.deleteIndexJob(assistant, path)
        val unqueued = state()
        assertFalse(dao.publishChunks(pending, listOf(chunk(pending))))
        assertEquals(unqueued, state())
    }

    @Test
    fun `复制到自身是完整无操作且不重写来源revision或已发布分块`() = runBlocking {
        create(context = MemoryWriteContext(sources = listOf(MemorySource(MemorySourceKind.MANUAL))))
        val pending = job()
        assertTrue(dao.publishChunks(pending, listOf(chunk(pending))))
        val before = state()
        repository.copyFiles(assistant, assistant)
        assertEquals(before, state())
    }

    @Test
    fun `同正文带可信context仍产生新revision保留现有证据并使旧索引任务失效`() = runBlocking {
        val first = create()
        val oldJob = job()
        val originalSource = repository.getSources(assistant, path).single()
        val manual = MemorySource(MemorySourceKind.MANUAL)
        assertTrue(repository.writeFile(
            assistant, path, body, first.version,
            MemoryWriteContext(MemoryCategory.ROLE_EXPERIENCE, listOf(manual)),
        ) is MemoryWriteResult.Success)
        val metadata = repository.getMetadata(assistant, path)!!
        val sources = repository.getSources(assistant, path)
        assertEquals(oldJob.contentHash, metadata.contentHash)
        assertNotEquals(oldJob.memoryRevision, metadata.revision)
        assertEquals(MemoryCategory.ROLE_EXPERIENCE.name, metadata.category)
        assertEquals(originalSource, sources.single { it.sourceId == originalSource.sourceId })
        assertEquals(2, sources.size)
        assertTrue(sources.all { it.status == "CURRENT" })
        assertEquals(metadata.revision, sources.single { it.kind == "MANUAL" }.memoryRevision)
        assertFalse(dao.publishChunks(oldJob, listOf(chunk(oldJob))))
        assertEquals(metadata.revision, job().memoryRevision)
    }
}
