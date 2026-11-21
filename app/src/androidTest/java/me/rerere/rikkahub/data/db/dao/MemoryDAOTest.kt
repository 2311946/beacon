package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemorySourceKind
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MemoryDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.memoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveStateReplacesBodyMetadataAndSourcesInvalidatesChunksAndRequeues() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "旧正文", 10L)
        val oldMetadata = save(file, "revision-old")
        val oldJob = dao.getIndexJobs(file.assistantId).single()
        assertTrue(dao.publishChunks(oldJob, listOf(chunk(oldMetadata, file.content))))
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())

        val updated = file.copy(content = "新正文", updatedAt = 20L)
        val metadata = oldMetadata.copy(
            category = "USER_FACT",
            contentHash = memoryContentHash(updated.content),
            revision = "revision-new",
            updatedAt = updated.updatedAt,
        )
        val sources = listOf(source(metadata).copy(sourceId = "replacement-source"))
        dao.saveState(updated, metadata, sources)

        assertEquals(updated, dao.getFile(file.assistantId, file.path))
        assertEquals(metadata, dao.getMetadata(file.assistantId, file.path))
        assertEquals(sources, dao.getSources(file.assistantId, file.path))
        assertTrue(dao.getChunks(file.assistantId, file.path).isEmpty())
        assertEquals(
            listOf(MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, 20L)),
            dao.getIndexJobs(file.assistantId),
        )
    }

    @Test
    fun saveStatesSecondFailureRollsBackUpdatedBodyAndAllFourSideTables() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "原正文", 10L)
        val metadata = save(file, "revision-original")
        // 保留旧分块和待处理任务，确保回滚也恢复 saveState 中的删除及任务替换。
        dao.upsertChunks(listOf(chunk(metadata, file.content)))
        val before = snapshot(file.assistantId, file.path)
        val updated = file.copy(content = "不得提交", updatedAt = 20L)
        val updatedMetadata = metadata.copy(
            contentHash = memoryContentHash(updated.content),
            revision = "revision-uncommitted",
            updatedAt = updated.updatedAt,
        )
        val second = file.copy(path = "notes/second.md", content = "第二项", updatedAt = 30L)
        val secondMetadata = metadata.copy(path = second.path, revision = "revision-second")
        // 第二项故意错配来源的复合键，在第一项完整执行后由 saveState 抛错。
        val invalidSource = source(secondMetadata).copy(path = "notes/missing.md")

        val failure = runCatching {
            dao.saveStates(
                listOf(updated, second),
                listOf(updatedMetadata, secondMetadata),
                listOf(listOf(source(updatedMetadata)), listOf(invalidSource)),
            )
        }.exceptionOrNull()

        assertTrue("应由第二项的来源键校验失败，实际异常：$failure", failure is IllegalArgumentException)
        assertEquals(before, snapshot(file.assistantId, file.path))
        assertMissing(second.assistantId, second.path)
        assertEquals(listOf(file), dao.getFilesOfAssistant(file.assistantId))
        assertForeignKeysValid()
    }

    @Test
    fun saveStatesSecondFailureAlsoRollsBackANewFirstFile() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/new.md", "不得残留的新正文", 10L)
        val metadata = MemoryMetadataEntity(
            file.assistantId, file.path, "ASSISTANT",
            contentHash = memoryContentHash(file.content), revision = "revision-new", updatedAt = 10L,
        )
        val second = file.copy(path = "notes/second.md")
        val failure = runCatching {
            dao.saveStates(
                listOf(file, second),
                listOf(metadata, metadata.copy(path = "notes/wrong.md")),
                listOf(listOf(source(metadata)), emptyList()),
            )
        }.exceptionOrNull()

        assertTrue("应由第二项的元数据键校验失败，实际异常：$failure", failure is IllegalArgumentException)
        assertMissing(file.assistantId, file.path)
        assertMissing(second.assistantId, second.path)
        assertTrue(dao.getFilesOfAssistant(file.assistantId).isEmpty())
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
        assertForeignKeysValid()
    }

    @Test
    fun sideTablesRequireTheWholeCompositeParentKey() = runBlocking {
        val first = MemoryFileEntity("assistant-a", "notes/first.md", "甲", 10L)
        val second = MemoryFileEntity("assistant-b", "notes/second.md", "乙", 20L)
        val metadata = save(first, "revision-a")
        save(second, "revision-b")
        val firstBefore = snapshot(first.assistantId, first.path)
        val secondBefore = snapshot(second.assistantId, second.path)
        // namespace 和 path 各自存在，但该组合没有父记录，不能分别匹配两个不同父行。
        val orphan = metadata.copy(path = second.path)
        val writes: List<suspend () -> Unit> = listOf(
            { dao.upsertMetadata(orphan) },
            { dao.upsertSources(listOf(source(orphan))) },
            { dao.upsertChunks(listOf(chunk(orphan, "孤立分块"))) },
            { dao.upsertIndexJob(MemoryIndexJobEntity(
                orphan.assistantId, orphan.path, orphan.revision, orphan.contentHash, orphan.updatedAt,
            )) },
        )
        writes.forEach { write ->
            val failure = runCatching { write() }.exceptionOrNull()
            assertTrue("应触发真实复合外键约束，实际异常：$failure",
                failure?.message?.contains("FOREIGN KEY", ignoreCase = true) == true)
            assertMissing(orphan.assistantId, orphan.path)
            assertEquals(firstBefore, snapshot(first.assistantId, first.path))
            assertEquals(secondBefore, snapshot(second.assistantId, second.path))
        }
        assertForeignKeysValid()
    }

    @Test
    fun deletingFileCascadesAllSideTablesOnlyForItsCompositeKey() = runBlocking {
        val files = listOf(
            MemoryFileEntity("assistant-a", "notes/shared.md", "甲", 10L),
            MemoryFileEntity("assistant-b", "notes/shared.md", "乙", 20L),
            MemoryFileEntity(MemoryScope.GLOBAL_NAMESPACE, "notes/shared.md", "共享", 30L),
            MemoryFileEntity("assistant-a", "notes/other.md", "同角色另一文件", 40L),
        )
        files.forEachIndexed { index, file ->
            val metadata = save(file, "revision-$index")
            dao.upsertChunks(listOf(chunk(metadata, file.content)))
        }
        val survivors = files.drop(1).map { snapshot(it.assistantId, it.path) }

        dao.deleteFile(files.first().assistantId, files.first().path)

        assertMissing(files.first().assistantId, files.first().path)
        files.drop(1).forEachIndexed { index, file ->
            assertEquals(survivors[index], snapshot(file.assistantId, file.path))
        }
        assertForeignKeysValid()
    }

    @Test
    fun deletingMetadataCascadesSourcesChunksAndJobsButKeepsBodyAndOtherNamespace() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "保留正文", 10L)
        val other = file.copy(assistantId = "assistant-b", content = "另一角色")
        listOf(file, other).forEach { entry ->
            val metadata = save(entry, "revision-${entry.assistantId}")
            dao.upsertChunks(listOf(chunk(metadata, entry.content)))
        }
        val otherBefore = snapshot(other.assistantId, other.path)

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM memory_metadata WHERE assistant_id = ? AND path = ?",
            arrayOf(file.assistantId, file.path),
        )

        assertEquals(file, dao.getFile(file.assistantId, file.path))
        assertNull(dao.getMetadata(file.assistantId, file.path))
        assertTrue(dao.getSources(file.assistantId, file.path).isEmpty())
        assertTrue(dao.getChunks(file.assistantId, file.path).isEmpty())
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
        assertEquals(otherBefore, snapshot(other.assistantId, other.path))
        assertForeignKeysValid()
    }

    @Test
    fun samePathSourceIdAndChunkIndexStayIsolatedAcrossNamespaces() = runBlocking {
        val files = listOf("assistant-a", "assistant-b", MemoryScope.GLOBAL_NAMESPACE).mapIndexed { index, namespace ->
            MemoryFileEntity(namespace, "notes/shared.md", "独立正文-$index", 10L + index)
        }
        val metadata = files.mapIndexed { index, file -> save(file, "revision-$index") }
        val otherBefore = files.drop(1).map { snapshot(it.assistantId, it.path) }
        val first = files.first()
        val job = dao.getIndexJobs(first.assistantId).single()
        val chunks = listOf(chunk(metadata.first(), first.content))

        assertTrue(dao.publishChunks(job, chunks))

        assertEquals(listOf(first), dao.getFilesOfAssistant(first.assistantId))
        assertEquals(chunks, dao.getChunks(first.assistantId, first.path))
        assertTrue(dao.getIndexJobs(first.assistantId).isEmpty())
        files.drop(1).forEachIndexed { index, file ->
            assertEquals(otherBefore[index], snapshot(file.assistantId, file.path))
            assertEquals(listOf(file), dao.getFilesOfAssistant(file.assistantId))
            val otherJob = dao.getIndexJobs(file.assistantId).single()
            val otherChunks = listOf(chunk(metadata[index + 1], file.content))
            assertTrue(dao.publishChunks(otherJob, otherChunks))
            assertEquals(otherChunks, dao.getChunks(file.assistantId, file.path))
            assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
            assertEquals(chunks, dao.getChunks(first.assistantId, first.path))
        }
        assertForeignKeysValid()
    }

    @Test
    fun publishChunksRejectsStaleRevisionWithoutConsumingCurrentJobOrReplacingChunks() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "正文不变也要检查修订", 10L)
        val oldMetadata = save(file, "revision-old")
        val oldJob = dao.getIndexJobs(file.assistantId).single()
        val currentMetadata = save(file.copy(updatedAt = 20L), "revision-current")
        val currentJob = dao.getIndexJobs(file.assistantId).single()
        assertEquals(oldJob.contentHash, currentJob.contentHash)
        assertNotEquals(oldJob.memoryRevision, currentJob.memoryRevision)
        val before = snapshot(file.assistantId, file.path)

        assertFalse(dao.publishChunks(oldJob, listOf(chunk(oldMetadata, "过期分块"))))
        assertEquals(before, snapshot(file.assistantId, file.path))
        val currentChunks = listOf(chunk(currentMetadata, file.content))
        assertTrue(dao.publishChunks(currentJob, currentChunks))
        val published = snapshot(file.assistantId, file.path)
        assertFalse(dao.publishChunks(oldJob, listOf(chunk(oldMetadata, "不得覆盖已发布分块"))))
        assertEquals(published, snapshot(file.assistantId, file.path))
        assertEquals(currentChunks, dao.getChunks(file.assistantId, file.path))
        // 已成功发布的同一任务不能再次使用。
        assertFalse(dao.publishChunks(currentJob, currentChunks))
        assertEquals(published, snapshot(file.assistantId, file.path))
    }

    @Test
    fun publishChunksRejectsDeletedAndRecreatedFileEvenWithSameContentAndTimestamp() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "重建后完全相同的正文", 10L)
        val oldMetadata = save(file, "revision-before-delete")
        val oldJob = dao.getIndexJobs(file.assistantId).single()
        val oldChunks = listOf(chunk(oldMetadata, file.content))
        dao.deleteFile(file.assistantId, file.path)
        assertFalse(dao.publishChunks(oldJob, oldChunks))
        assertMissing(file.assistantId, file.path)

        val recreatedMetadata = save(file, "revision-after-recreate")
        val recreatedJob = dao.getIndexJobs(file.assistantId).single()
        assertEquals(oldJob.contentHash, recreatedJob.contentHash)
        assertEquals(oldJob.queuedAt, recreatedJob.queuedAt)
        assertNotEquals(oldJob.memoryRevision, recreatedJob.memoryRevision)
        val before = snapshot(file.assistantId, file.path)

        assertFalse(dao.publishChunks(oldJob, oldChunks))
        assertEquals(before, snapshot(file.assistantId, file.path))
        val recreatedChunks = listOf(chunk(recreatedMetadata, file.content))
        assertTrue(dao.publishChunks(recreatedJob, recreatedChunks))
        assertEquals(recreatedChunks, dao.getChunks(file.assistantId, file.path))
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
    }

    @Test
    fun allActiveFilesIncludeLegacyAndAllNamespacesButExcludeInactiveMetadata() = runBlocking {
        val first = MemoryFileEntity("assistant-b", "notes/shared.md", "乙", 10L)
        val second = first.copy(assistantId = "assistant-a", content = "甲")
        val global = first.copy(assistantId = MemoryScope.GLOBAL_NAMESPACE, path = "notes/global.md")
        val legacy = first.copy(path = "notes/legacy.md")
        listOf(first, second, global).forEach { save(it, "revision-${it.assistantId}") }
        dao.upsertFile(legacy)
        listOf("ARCHIVED", "QUARANTINED", "SUPERSEDED").forEach { lifecycle ->
            val inactive = first.copy(path = "notes/$lifecycle.md")
            val metadata = save(inactive, "revision-$lifecycle")
            dao.upsertMetadata(metadata.copy(lifecycle = lifecycle))
        }

        assertEquals(listOf(global, legacy, second, first), dao.getAllActiveFiles())
    }

    @Test
    fun observeIndexJobsIncludesAllNamespacesWithStableQueuePathAndAssistantOrder() = runBlocking {
        val files = listOf(
            MemoryFileEntity("assistant-b", "notes/shared.md", "乙", 20L),
            MemoryFileEntity("assistant-a", "notes/shared.md", "甲", 20L),
            MemoryFileEntity(MemoryScope.GLOBAL_NAMESPACE, "notes/a.md", "共享", 20L),
            MemoryFileEntity("assistant-a", "notes/z.md", "最早", 10L),
        )
        files.forEachIndexed { index, file -> save(file, "revision-$index") }
        val expected = files.sortedWith(compareBy({ it.updatedAt }, { it.path }, { it.assistantId }))
        val observed = withTimeout(5_000) { dao.observeIndexJobs().first() }
        assertEquals(expected.map { it.assistantId to it.path }, observed.map { it.assistantId to it.path })

        dao.deleteFile(files.first().assistantId, files.first().path)
        assertEquals(observed.dropLast(1), withTimeout(5_000) { dao.observeIndexJobs().first() })
    }

    @Test
    fun ensureIndexJobQueuesCurrentMetadataOnlyOnceAndNeverOverwritesExistingJob() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "完整正文", 10L)
        val metadata = save(file, "revision-current").copy(updatedAt = 42L)
        dao.upsertMetadata(metadata)
        dao.deleteIndexJob(file.assistantId, file.path)

        assertTrue(dao.ensureIndexJob(file.assistantId, file.path))
        val expected = MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, 42L)
        assertEquals(listOf(expected), dao.getIndexJobs(file.assistantId))
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        val existing = expected.copy(memoryRevision = "revision-existing", contentHash = "旧任务hash", queuedAt = 1L)
        dao.upsertIndexJob(existing)
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        assertEquals(listOf(existing), dao.getIndexJobs(file.assistantId))
    }

    @Test
    fun ensureIndexJobRejectsMissingLegacyInactiveAndMismatchedBodyWithoutWriting() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "原文", 10L)
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        dao.upsertFile(file)
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        val metadata = save(file, "revision-current")
        dao.deleteIndexJob(file.assistantId, file.path)
        listOf("ARCHIVED", "QUARANTINED", "SUPERSEDED").forEach { lifecycle ->
            dao.upsertMetadata(metadata.copy(lifecycle = lifecycle))
            assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        }
        dao.upsertMetadata(metadata.copy(contentHash = metadata.contentHash.take(12)))
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        dao.upsertMetadata(metadata)
        dao.upsertFile(file.copy(content = "绕过元数据修改正文"))
        assertFalse(dao.ensureIndexJob(file.assistantId, file.path))
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
        assertFalse(dao.ensureIndexJob("assistant-b", file.path))
    }

    @Test
    fun readIndexedChunksReturnsOnlyWholeCurrentRevisionAndFullBodyHash() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "完整正文", 10L)
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.upsertFile(file)
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        val metadata = save(file, "revision-current")
        val first = chunk(metadata, "第一块")
        val second = first.copy(chunkIndex = 1, content = "第二块")
        dao.upsertChunks(listOf(first, second))
        assertEquals(listOf(first, second), dao.readIndexedChunks(file.assistantId, file.path))
        assertTrue(dao.readIndexedChunks("assistant-b", file.path).isEmpty())

        listOf(
            second.copy(memoryRevision = "revision-stale"),
            second.copy(contentHash = metadata.contentHash.take(12)),
            second.copy(contentHash = memoryContentHash("其他正文")),
        ).forEach { stale ->
            dao.upsertChunks(listOf(stale))
            assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        }
        dao.upsertChunks(listOf(second))
        listOf("ARCHIVED", "QUARANTINED", "SUPERSEDED").forEach { lifecycle ->
            dao.upsertMetadata(metadata.copy(lifecycle = lifecycle))
            assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        }
        dao.upsertMetadata(metadata.copy(contentHash = metadata.contentHash.take(12)))
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.upsertMetadata(metadata)
        dao.upsertFile(file.copy(content = "绕过元数据修改正文"))
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.upsertFile(file)
        assertEquals(listOf(first, second), dao.readIndexedChunks(file.assistantId, file.path))
        dao.deleteFile(file.assistantId, file.path)
        val recreated = save(file, "revision-recreated")
        dao.upsertChunks(listOf(first, second))
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.upsertChunks(listOf(first.copy(memoryRevision = recreated.revision), second.copy(memoryRevision = recreated.revision)))
        assertEquals(2, dao.readIndexedChunks(file.assistantId, file.path).size)
    }

    @Test
    fun getIndexJobUsesBothNamespaceAndPathAndPreservesExactJobIdentity() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/shared.md", "正文", 10L)
        val metadata = save(file, "revision-current")
        val otherNamespace = file.copy(assistantId = "assistant-b")
        val otherPath = file.copy(path = "notes/other.md")
        save(otherNamespace, "revision-other-namespace")
        save(otherPath, "revision-other-path")
        val expected = MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, 10L)
        assertEquals(expected, dao.getIndexJob(file.assistantId, file.path))
        assertNull(dao.getIndexJob("missing", file.path))
        assertNull(dao.getIndexJob(file.assistantId, "notes/missing.md"))
        assertFalse(dao.publishChunks(expected.copy(queuedAt = 11L), listOf(chunk(metadata, file.content))))
        assertEquals(expected, dao.getIndexJob(file.assistantId, file.path))
        dao.deleteIndexJob(file.assistantId, file.path)
        assertNull(dao.getIndexJob(file.assistantId, file.path))
        assertTrue(dao.ensureIndexJob(file.assistantId, file.path))
        assertEquals(expected, dao.getIndexJob(file.assistantId, file.path))
        assertTrue(dao.publishChunks(expected, listOf(chunk(metadata, file.content))))
        assertNull(dao.getIndexJob(file.assistantId, file.path))
        assertEquals("revision-other-namespace", dao.getIndexJob(otherNamespace.assistantId, otherNamespace.path)?.memoryRevision)
        assertEquals("revision-other-path", dao.getIndexJob(otherPath.assistantId, otherPath.path)?.memoryRevision)
    }

    @Test
    fun isEmbeddingReadyRequiresEveryChunkToMatchCurrentMetadataAndVectorLayout() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/vector.md", "向量正文", 10L)
        val profile = "provider:model"
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.upsertFile(file)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        val metadata = save(file, "revision-current")
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        val first = chunk(metadata, "第一块").copy(
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
        // 轻量查询不读取正文；实际读取仍须验证完整正文哈希。
        assertTrue(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        assertTrue(dao.readIndexedChunks(file.assistantId, file.path).isEmpty())
        dao.deleteChunks(file.assistantId, file.path)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
        dao.deleteFile(file.assistantId, file.path)
        assertFalse(dao.isEmbeddingReady(file.assistantId, file.path, profile))
    }

    @Test
    fun publishVectorChunksRoundTripsBlobProfileAndDimensions() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/vector.md", "向量正文", 10L)
        val metadata = save(file, "revision-vector")
        val vector = chunk(metadata, file.content).copy(
            embedding = byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64),
            embeddingProfile = "provider:model",
            embeddingDimensions = 2,
        )
        val job = dao.getIndexJobs(file.assistantId).single()

        assertTrue(dao.publishChunks(job, listOf(vector)))
        val stored = dao.readIndexedChunks(file.assistantId, file.path).single()
        assertArrayEquals(vector.embedding, stored.embedding)
        assertEquals(vector.embeddingProfile, stored.embeddingProfile)
        assertEquals(vector.embeddingDimensions, stored.embeddingDimensions)
        assertTrue(dao.getIndexJobs(file.assistantId).isEmpty())
    }

    @Test
    fun invalidVectorBatchesAreRejectedBeforeReplacingChunksOrConsumingJob() = runBlocking {
        val file = MemoryFileEntity("assistant-a", "notes/vector.md", "向量正文", 10L)
        val metadata = save(file, "revision-vector")
        val legacy = chunk(metadata, "保留旧的无向量分块")
        dao.upsertChunks(listOf(legacy))
        val job = dao.getIndexJobs(file.assistantId).single()
        val vector = legacy.copy(embedding = ByteArray(8), embeddingProfile = "provider:model", embeddingDimensions = 2)
        val invalid = listOf(
            listOf(vector.copy(embeddingProfile = "")),
            listOf(vector.copy(embeddingProfile = " ")),
            listOf(vector.copy(embeddingDimensions = 0)),
            listOf(vector.copy(embeddingDimensions = -1)),
            listOf(vector.copy(embeddingDimensions = Int.MAX_VALUE)),
            listOf(vector.copy(embedding = ByteArray(7))),
            listOf(vector.copy(embedding = ByteArray(0))),
            listOf(vector, legacy.copy(chunkIndex = 1)),
            listOf(vector, vector.copy(chunkIndex = 1, embeddingProfile = "other:model")),
            listOf(vector, vector.copy(chunkIndex = 1, embedding = ByteArray(12), embeddingDimensions = 3)),
        )
        invalid.forEach { batch ->
            val failure = runCatching { dao.publishChunks(job, batch) }.exceptionOrNull()
            assertTrue("非法向量批次必须拒绝，实际异常：$failure", failure is IllegalArgumentException)
            assertEquals(listOf(legacy), dao.getChunks(file.assistantId, file.path))
            assertEquals(listOf(job), dao.getIndexJobs(file.assistantId))
        }
        assertTrue(dao.publishChunks(job, listOf(legacy)))
        val stored = dao.readIndexedChunks(file.assistantId, file.path).single()
        assertNull(stored.embedding)
        assertEquals("", stored.embeddingProfile)
        assertEquals(0, stored.embeddingDimensions)
    }

    private suspend fun save(file: MemoryFileEntity, revision: String): MemoryMetadataEntity {
        val metadata = MemoryMetadataEntity(
            assistantId = file.assistantId,
            path = file.path,
            scopeKind = MemoryScope.of(file.assistantId).kind.name,
            contentHash = memoryContentHash(file.content),
            revision = revision,
            updatedAt = file.updatedAt,
        )
        dao.saveState(file, metadata, listOf(source(metadata)))
        return metadata
    }

    private fun source(metadata: MemoryMetadataEntity) = MemorySourceEntity(
        assistantId = metadata.assistantId,
        path = metadata.path,
        sourceId = "same-source-id",
        kind = MemorySourceKind.MANUAL.name,
        memoryRevision = metadata.revision,
        sourceJson = JsonInstant.encodeToString(MemorySource(kind = MemorySourceKind.MANUAL)),
    )

    private fun chunk(metadata: MemoryMetadataEntity, content: String) = MemoryChunkEntity(
        assistantId = metadata.assistantId,
        path = metadata.path,
        chunkIndex = 0,
        memoryRevision = metadata.revision,
        contentHash = metadata.contentHash,
        chunkerVersion = 1,
        content = content,
    )

    private suspend fun snapshot(namespace: String, path: String): List<Any?> = listOf(
        dao.getFile(namespace, path),
        dao.getMetadata(namespace, path),
        dao.getSources(namespace, path),
        dao.getChunks(namespace, path),
        dao.getIndexJobs(namespace).filter { it.path == path },
    )

    private suspend fun assertMissing(namespace: String, path: String) {
        assertNull(dao.getFile(namespace, path))
        assertNull(dao.getMetadata(namespace, path))
        assertTrue(dao.getSources(namespace, path).isEmpty())
        assertTrue(dao.getChunks(namespace, path).isEmpty())
        assertTrue(dao.getIndexJobs(namespace).none { it.path == path })
    }

    private fun assertForeignKeysValid() {
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse("不应留下孤立的记忆旁表行", it.moveToFirst())
        }
    }
}
