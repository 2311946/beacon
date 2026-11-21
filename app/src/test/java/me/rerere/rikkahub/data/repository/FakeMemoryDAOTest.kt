package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.model.memoryContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMemoryDAOTest {
    @Test
    fun `全库任务流在新增替换删除及级联删除时持续发出有序快照`() = runBlocking {
        val dao = FakeMemoryDAO()
        val snapshots = Channel<List<MemoryIndexJobEntity>>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            dao.observeIndexJobs().collect { snapshots.send(it) }
        }
        suspend fun next() = withTimeout(5_000) { snapshots.receive() }
        try {
            assertEquals(emptyList<MemoryIndexJobEntity>(), next())
            val first = MemoryIndexJobEntity("assistant-b", "notes/shared.md", "revision-b", "hash-b", 20L)
            val second = first.copy(assistantId = "assistant-a", memoryRevision = "revision-a")
            val earlier = first.copy(path = "notes/earlier.md", queuedAt = 10L)
            dao.upsertIndexJob(first)
            assertEquals(listOf(first), next())
            dao.upsertIndexJob(second)
            assertEquals(listOf(second, first), next())
            dao.upsertIndexJob(earlier)
            assertEquals(listOf(earlier, second, first), next())
            val replacement = first.copy(memoryRevision = "revision-new", queuedAt = 5L)
            dao.upsertIndexJob(replacement)
            assertEquals(listOf(replacement, earlier, second), next())
            dao.deleteIndexJob(earlier.assistantId, earlier.path)
            assertEquals(listOf(replacement, second), next())
            val file = MemoryFileEntity(second.assistantId, second.path, "正文", 1L)
            dao.upsertFile(file)
            dao.upsertMetadata(MemoryMetadataEntity(
                file.assistantId, file.path, "ASSISTANT", contentHash = memoryContentHash(file.content),
                revision = second.memoryRevision, updatedAt = file.updatedAt,
            ))
            dao.deleteFilesOfAssistant(second.assistantId)
            assertEquals(listOf(replacement), next())
            dao.deleteFile(replacement.assistantId, replacement.path)
            assertEquals(emptyList<MemoryIndexJobEntity>(), next())
        } finally {
            observer.cancel()
            snapshots.close()
        }
    }

    @Test
    fun `任务点查按角色与路径隔离且发布验证完整任务身份`() = runBlocking {
        val dao = FakeMemoryDAO()
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "正文", 10L)
        val metadata = MemoryMetadataEntity(
            file.assistantId, file.path, "ASSISTANT", contentHash = memoryContentHash(file.content),
            revision = "revision-current", updatedAt = file.updatedAt,
        )
        dao.saveState(file, metadata, emptyList())
        val job = MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, file.updatedAt)
        val otherNamespace = job.copy(assistantId = "assistant-b")
        val otherPath = job.copy(path = "notes/other.md")
        dao.upsertIndexJob(otherNamespace)
        dao.upsertIndexJob(otherPath)
        assertEquals(job, dao.getIndexJob(file.assistantId, file.path))
        assertEquals(otherNamespace, dao.getIndexJob(otherNamespace.assistantId, otherNamespace.path))
        assertEquals(otherPath, dao.getIndexJob(otherPath.assistantId, otherPath.path))
        assertNull(dao.getIndexJob("missing", file.path))
        assertNull(dao.getIndexJob(file.assistantId, "notes/missing.md"))
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        val chunk = MemoryChunkEntity(file.assistantId, file.path, 0, metadata.revision, metadata.contentHash, 1, file.content)
        assertFalse(dao.publishChunks(job.copy(queuedAt = 11L), listOf(chunk)))
        assertEquals(job, dao.getIndexJob(file.assistantId, file.path))
        dao.deleteIndexJob(file.assistantId, file.path)
        assertTrue(dao.ensureIndexJob(file.assistantId, file.path))
        assertEquals(job, dao.getIndexJob(file.assistantId, file.path))
        assertTrue(dao.publishChunks(job, listOf(chunk)))
        assertNull(dao.getIndexJob(file.assistantId, file.path))
        assertEquals(otherNamespace, dao.getIndexJob(otherNamespace.assistantId, otherNamespace.path))
        assertEquals(otherPath, dao.getIndexJob(otherPath.assistantId, otherPath.path))
    }

    @Test
    fun `向量就绪要求当前ACTIVE元数据及非空且全部有效的分块`() = runBlocking {
        val dao = FakeMemoryDAO()
        val file = MemoryFileEntity("assistant-a", "notes/vector.md", "向量正文", 10L)
        val profile = "provider:model"
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertFile(file)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        val metadata = MemoryMetadataEntity(
            file.assistantId, file.path, "ASSISTANT", contentHash = memoryContentHash(file.content),
            revision = "revision-current", updatedAt = file.updatedAt,
        )
        dao.upsertMetadata(metadata)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        val first = MemoryChunkEntity(
            file.assistantId, file.path, 0, metadata.revision, metadata.contentHash, 1, "第一块",
            embedding = ByteArray(8), embeddingProfile = profile, embeddingDimensions = 2,
        )
        val second = first.copy(chunkIndex = 1, content = "第二块")
        dao.upsertChunks(listOf(first, second))
        assertTrue(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        assertFalse(dao.isEmbeddingReady("assistant-b", file.path, profile))
        assertFalse(dao.isEmbeddingReady(file.assistantId, "notes/other.md", profile))
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, "other:model"))
        listOf(
            second.copy(memoryRevision = "revision-stale"),
            second.copy(contentHash = metadata.contentHash.take(12)),
            second.copy(contentHash = memoryContentHash("其他正文")),
            second.copy(embedding = null),
            second.copy(embeddingProfile = "other:model"),
            second.copy(embeddingProfile = ""),
            second.copy(chunkerVersion = 0),
            second.copy(chunkerVersion = 2),
            second.copy(embeddingDimensions = 0),
            second.copy(embeddingDimensions = -1),
            second.copy(embeddingDimensions = Int.MAX_VALUE),
            second.copy(embedding = ByteArray(7)),
            second.copy(embedding = ByteArray(12)),
            second.copy(embedding = ByteArray(0)),
        ).forEach { invalid ->
            dao.upsertChunks(listOf(invalid))
            assertFalse("非法分块不能被就绪查询忽略：$invalid", dao.isEmbeddingReady(file.assistantId, file.path, profile))
        }
        dao.upsertChunks(listOf(second))
        listOf("ARCHIVED", "QUARANTINED", "SUPERSEDED").forEach { lifecycle ->
            dao.upsertMetadata(metadata.copy(lifecycle = lifecycle))
            assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        }
        dao.upsertMetadata(metadata.copy(revision = "revision-new"))
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertMetadata(metadata.copy(contentHash = memoryContentHash("新正文")))
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertMetadata(metadata)
        assertTrue(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertFile(file.copy(content = "绕过元数据修改正文"))
        assertTrue(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.deleteChunks(file.assistantId, file.path)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertChunks(listOf(first))
        dao.deleteFile(file.assistantId, file.path)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertMetadata(metadata)
        dao.upsertChunks(listOf(first))
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
    }

    @Test
    fun `全库ACTIVE查询兼容旧文件且补任务幂等并使用元数据时间`() = runBlocking {
        val dao = FakeMemoryDAO()
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "正文", 10L)
        val legacy = file.copy(assistantId = "assistant-b")
        val inactive = file.copy(path = "notes/inactive.md")
        dao.upsertFiles(listOf(legacy, inactive, file))
        val metadata = MemoryMetadataEntity(
            file.assistantId, file.path, "ASSISTANT", contentHash = memoryContentHash(file.content),
            revision = "revision-current", updatedAt = 42L,
        )
        dao.upsertMetadata(metadata)
        dao.upsertMetadata(metadata.copy(path = inactive.path, lifecycle = "QUARANTINED"))
        assertEquals(listOf(file, legacy), dao.getAllActiveFiles())
        assertFalse(dao.ensureIndexJob(legacy.assistantId, legacy.path))
        assertFalse(dao.ensureIndexJob(inactive.assistantId, inactive.path))
        assertTrue(dao.ensureIndexJob(file.assistantId, file.path))
        assertEquals(
            listOf(MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, 42L)),
            dao.getIndexJobs(file.assistantId),
        )
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
    }
}
