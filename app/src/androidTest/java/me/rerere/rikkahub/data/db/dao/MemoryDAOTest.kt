package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
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
