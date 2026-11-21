package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test

class MemoryEmbeddingIndexerTest {
    private val config = MemoryEmbeddingSettings(enabled = true)
    private val success = object : MemoryEmbedder {
        override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>) = inputs.map { floatArrayOf(1f, 2f) }
    }

    @Test fun `publish BLOB consumes durable job and matches shared chunk boundaries`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        val body = "喜欢咖啡\n\n" + "中文".repeat(650)
        repo.writeFile("a", "/drink.md", body)
        val indexer = MemoryEmbeddingIndexer(dao, success) { config }
        assertTrue(indexer.index(dao.getIndexJobs("a").single()))
        assertTrue(dao.getIndexJobs("a").isEmpty())
        val chunks = dao.readIndexedChunks("a", "/drink.md")
        assertEquals(memoryRecallChunks(body).map { it.content }, chunks.map { it.content })
        chunks.forEach {
            assertEquals(embeddingProfile(config), it.embeddingProfile)
            assertArrayEquals(floatArrayOf(1f, 2f), MemoryVectorCodec.decode(it.embedding!!, it.embeddingDimensions), 0f)
        }
    }

    @Test fun `configuration revoked during metadata read prevents new outbound batch`() = runBlocking {
        for (changed in listOf(config.copy(enabled = false), config.copy(endpoint = "https://other.example/v1/embeddings"))) {
            var live = config
            var reads = 0
            var armed = false
            val dao = object : FakeMemoryDAO() {
                override suspend fun getMetadata(assistantId: String, path: String) =
                    super.getMetadata(assistantId, path).also {
                        if (armed && ++reads == 3) live = changed
                    }
            }
            val repo = MemoryRepository(dao)
            repo.writeFile("a", "/drink.md", "喜欢咖啡")
            val job = dao.getIndexJobs("a").single()
            var calls = 0
            val embedder = object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    calls++
                    return inputs.map { floatArrayOf(1f, 2f) }
                }
            }
            armed = true
            assertFalse(MemoryEmbeddingIndexer(dao, embedder) { live }.index(job))
            assertEquals(0, calls)
            assertEquals(listOf(job), dao.getIndexJobs("a"))
            assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
        }
    }

    @Test fun `empty body consumes job without API and is not requeued`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/empty.md", "")
        val embedder = object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> = error("must not upload")
        }
        assertTrue(MemoryEmbeddingIndexer(dao, embedder) { config }.index(dao.getIndexJobs("a").single()))
        repo.prepareEmbeddingJobs(embeddingProfile(config))
        assertTrue(dao.getIndexJobs("a").isEmpty())
        assertTrue(dao.getChunks("a", "/empty.md").isEmpty())
    }

    @Test fun `API failure keeps job for retry without partial vector`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/drink.md", "喜欢咖啡")
        val original = dao.getIndexJobs("a").single()
        val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> = error("offline")
        }) { config }
        assertFalse(indexer.index(original))
        assertEquals(listOf(original), dao.getIndexJobs("a"))
        assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
    }

    @Test fun `failure in second batch never publishes first batch and retry publishes all chunks`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        val body = (1..32).joinToString("\n\n") { "第${it}条饮品偏好" }
        val expected = memoryRecallChunks(body).map { it.content }
        assertEquals(32, expected.size)
        repo.writeFile("a", "/drink.md", body)
        val original = dao.getIndexJobs("a").single()
        val requests = mutableListOf<List<String>>()
        val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                requests += inputs.toList()
                assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
                assertEquals(listOf(original), dao.getIndexJobs("a"))
                if (requests.size == 2) error("第二批请求失败")
                return inputs.map { floatArrayOf(1f, 2f) }
            }
        }) { config }

        assertFalse(indexer.index(original))
        assertEquals(listOf(16, 16), requests.map { it.size })
        assertEquals(expected, requests.flatten())
        assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
        assertEquals(listOf(original), dao.getIndexJobs("a"))

        assertTrue(MemoryEmbeddingIndexer(dao, success) { config }.index(original))
        assertEquals(expected, dao.readIndexedChunks("a", "/drink.md").map { it.content })
        assertTrue(dao.getIndexJobs("a").isEmpty())
    }

    @Test fun `NaN vector rejects whole batch and preserves durable job`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/drink.md", "喜欢咖啡\n\n喜欢茶")
        val original = dao.getIndexJobs("a").single()
        var calls = 0
        val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                calls++
                assertEquals(2, inputs.size)
                return listOf(floatArrayOf(1f, 2f), floatArrayOf(Float.NaN, 2f))
            }
        }) { config }

        assertFalse(indexer.index(original))
        assertEquals(1, calls)
        assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
        assertEquals(listOf(original), dao.getIndexJobs("a"))
    }

    @Test fun `model change during either batch rejects publication and keeps job for new model`() = runBlocking {
        for (changedBatch in 1..2) {
            val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
            val body = (1..32).joinToString("\n\n") { "第${it}条饮品偏好" }
            repo.writeFile("a", "/drink.md", body)
            val original = dao.getIndexJobs("a").single()
            var live = config
            var calls = 0
            val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    assertEquals(live, config)
                    calls++
                    if (calls == changedBatch) live = config.copy(model = "replacement-model")
                    return inputs.map { floatArrayOf(1f, 2f) }
                }
            }) { live }

            assertFalse(indexer.index(original))
            assertEquals(changedBatch, calls)
            assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
            assertEquals(listOf(original), dao.getIndexJobs("a"))
            assertTrue(MemoryEmbeddingIndexer(dao, success) { live }.index(original))
            val chunks = dao.readIndexedChunks("a", "/drink.md")
            assertEquals(memoryRecallChunks(body).size, chunks.size)
            assertTrue(chunks.all { it.embeddingProfile == embeddingProfile(live) })
            assertTrue(dao.getIndexJobs("a").isEmpty())
        }
    }

    @Test fun `edit while embedding in flight cannot publish stale chunks or consume newer job`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/drink.md", "喜欢咖啡")
        val original = dao.getIndexJobs("a").single()
        val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                repo.writeFile("a", "/drink.md", "喜欢茶")
                return inputs.map { floatArrayOf(1f, 2f) }
            }
        }) { config }
        assertFalse(indexer.index(original))
        assertNotEquals(original.memoryRevision, dao.getIndexJobs("a").single().memoryRevision)
        assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
    }

    @Test fun `disable during embedding rejects publication`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/drink.md", "喜欢咖啡")
        var live = config
        val indexer = MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                live = config.copy(enabled = false)
                return inputs.map { floatArrayOf(1f, 2f) }
            }
        }) { live }
        assertFalse(indexer.index(dao.getIndexJobs("a").single()))
        assertTrue(dao.getChunks("a", "/drink.md").isEmpty())
    }

    @Test fun `archived file never uploads and model change requeues without changing revision`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        repo.writeFile("a", "/drink.md", "喜欢咖啡")
        val oldJob = dao.getIndexJobs("a").single()
        val indexer = MemoryEmbeddingIndexer(dao, success) { config }
        assertTrue(indexer.index(oldJob))
        repo.prepareEmbeddingJobs(embeddingProfile(config))
        assertTrue(dao.getIndexJobs("a").isEmpty())
        repo.prepareEmbeddingJobs(embeddingProfile(config.copy(model = "new-model")))
        assertEquals(oldJob.memoryRevision, dao.getIndexJobs("a").single().memoryRevision)
        repo.setLifecycle("a", "/drink.md", MemoryLifecycle.ARCHIVED)
        assertFalse(indexer.index(oldJob))
        assertTrue(dao.getIndexJobs("a").isEmpty())
    }
}
