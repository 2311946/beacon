package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import me.rerere.rikkahub.data.model.MemoryLifecycle
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test

class HybridMemoryRecallTest {
    private fun hit(path: String, score: Double, updated: Long = 0) = MemoryRecallHit(path, "v", 0, path, updated, score)

    @Test fun `semantic finds non lexical chunk and weight changes ordering`() {
        val lexical = listOf(hit("coffee", 8.0))
        val semantic = listOf(hit("drink", .95), hit("coffee", .1))
        assertEquals("drink", mixMemoryRecall(lexical, semantic, .9, 6, 0).first().path)
        assertEquals("coffee", mixMemoryRecall(lexical, semantic, .1, 6, 0).first().path)
    }

    @Test fun `zero weight and unavailable semantic preserve lexical score exactly`() {
        val lexical = listOf(hit("coffee", 8.0))
        assertEquals(lexical, mixMemoryRecall(lexical, listOf(hit("drink", .95)), 0.0, 6, 0))
        assertEquals(lexical, mixMemoryRecall(lexical, emptyList(), .6, 6, 0))
    }

    @Test fun `time decay and deterministic ties are retained`() {
        assertEquals("new", mixMemoryRecall(emptyList(), listOf(hit("old", .8), hit("new", .8, 90L * 86400000)), 1.0, 6, 90L * 86400000).first().path)
        assertEquals(listOf("a", "b"), mixMemoryRecall(emptyList(), listOf(hit("b", .8), hit("a", .8)), 1.0, 6, 0).map { it.path })
    }

    @Test fun `disabled and no index never call embedding`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        repo.writeFile("a", "/coffee.md", "喜欢咖啡")
        val embedder = object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> = error("not called")
        }
        val disabled = HybridMemoryRecall(repo, embedder) { MemoryEmbeddingSettings() }
        val noIndex = HybridMemoryRecall(repo, embedder) { MemoryEmbeddingSettings(enabled = true) }
        assertEquals(1, disabled.recall("a", "咖啡", 6, 0).size)
        assertEquals(1, noIndex.recall("a", "咖啡", 6, 0).size)
        assertTrue(noIndex.recall("b", "咖啡", 6, 0).isEmpty())
    }

    private suspend fun indexed(dao: FakeMemoryDAO, repo: MemoryRepository) {
        repo.writeFile("a", "/drink.md", "喜欢喝拿铁")
        val config = MemoryEmbeddingSettings(enabled = true)
        assertTrue(MemoryEmbeddingIndexer(dao, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>) = inputs.map { floatArrayOf(1f, 0f) }
        }) { config }.index(dao.getIndexJobs("a").single()))
    }

    @Test fun `configuration revoked during index read prevents outbound query`() = runBlocking {
        val initial = MemoryEmbeddingSettings(enabled = true)
        for (changed in listOf(initial.copy(enabled = false), initial.copy(apiKey = "new-key"), initial.copy(endpoint = "https://other.example/v1/embeddings"))) {
            var live = initial
            var armed = false
            val dao = object : FakeMemoryDAO() {
                override suspend fun readIndexedChunks(assistantId: String, path: String) =
                    super.readIndexedChunks(assistantId, path).also {
                        if (armed) live = changed
                    }
            }
            val repo = MemoryRepository(dao)
            indexed(dao, repo)
            var calls = 0
            val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    calls++
                    return listOf(floatArrayOf(1f, 0f))
                }
            }) { live }
            armed = true
            assertEquals(recallLocalMemories("拿铁", repo.getActiveFiles("a"), 6, 0), recall.recall("a", "拿铁", 6, 0))
            assertEquals(0, calls)
        }
    }

    @Test fun `semantic recall and namespace isolation`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        indexed(dao, repo)
        val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>) = listOf(floatArrayOf(1f, 0f))
        }) { MemoryEmbeddingSettings(enabled = true) }
        assertEquals("喜欢喝拿铁", recall.recall("a", "咖啡", 6, 0).single().content)
        assertTrue(recall.recall("other", "咖啡", 6, 0).isEmpty())
    }

    @Test fun `failure falls back and cancellation propagates`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        indexed(dao, repo)
        repo.writeFile("a", "/coffee.md", "喜欢咖啡")
        var cancel = false
        val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                if (cancel) throw CancellationException("cancel")
                error("API unavailable")
            }
        }) { MemoryEmbeddingSettings(enabled = true) }
        assertEquals("/coffee.md", recall.recall("a", "咖啡", 6, 0).single().path)
        cancel = true
        try { recall.recall("a", "咖啡", 6, 0); fail("must cancel") } catch (_: CancellationException) { }
    }

    @Test fun `slow embedding is cancelled and falls back within bounded query budget`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        indexed(dao, repo)
        repo.writeFile("a", "/coffee.md", "喜欢咖啡")
        var cancelled = false
        val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }) { MemoryEmbeddingSettings(enabled = true) }
        val result = kotlinx.coroutines.withTimeout(5000) { recall.recall("a", "咖啡", 6, 0) }
        assertEquals(listOf("/coffee.md"), result.map { it.path })
        assertTrue(cancelled)
    }

    @Test fun `NaN query vector preserves lexical fallback without semantic injection`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        indexed(dao, repo)
        repo.writeFile("a", "/coffee.md", "喜欢咖啡")
        val expected = recallLocalMemories("咖啡", repo.getActiveFiles("a"), 6, 0)
        var calls = 0
        val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                calls++
                return listOf(floatArrayOf(Float.NaN, 0f))
            }
        }) { MemoryEmbeddingSettings(enabled = true) }

        assertEquals(expected, recall.recall("a", "咖啡", 6, 0))
        assertEquals(1, calls)
        assertEquals(listOf("/coffee.md"), expected.map { it.path })
    }

    @Test fun `edit while query is suspended excludes old semantic and lexical snapshots`() = runBlocking {
        for (query in listOf("咖啡", "拿铁")) {
            val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
            indexed(dao, repo)
            repo.writeFile("a", "/coffee.md", "咖啡和拿铁都喜欢")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    started.complete(Unit)
                    release.await()
                    return listOf(floatArrayOf(1f, 0f))
                }
            }) { MemoryEmbeddingSettings(enabled = true) }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { recall.recall("a", query, 6, 0) }
            try {
                assertTrue(started.isCompleted)
                assertFalse(pending.isCompleted)
                repo.writeFile("a", "/drink.md", "现在只喜欢绿茶")
                assertEquals("现在只喜欢绿茶", repo.getFile("a", "/drink.md")?.content)
            } finally {
                release.complete(Unit)
            }

            val hits = pending.await()
            assertEquals(recallLocalMemories(query, repo.getActiveFiles("a"), 6, 0), hits)
            assertEquals(listOf("/coffee.md"), hits.map { it.path })
            assertTrue(hits.none { it.content == "喜欢喝拿铁" })
        }
    }

    @Test fun `delete while query is suspended excludes old semantic and lexical snapshots`() = runBlocking {
        for (query in listOf("咖啡", "拿铁")) {
            val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
            indexed(dao, repo)
            repo.writeFile("a", "/coffee.md", "咖啡和拿铁都喜欢")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    started.complete(Unit)
                    release.await()
                    return listOf(floatArrayOf(1f, 0f))
                }
            }) { MemoryEmbeddingSettings(enabled = true) }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { recall.recall("a", query, 6, 0) }
            try {
                assertTrue(started.isCompleted)
                assertFalse(pending.isCompleted)
                repo.deleteFile("a", "/drink.md")
                assertNull(repo.getFile("a", "/drink.md"))
            } finally {
                release.complete(Unit)
            }

            val hits = pending.await()
            assertEquals(recallLocalMemories(query, repo.getActiveFiles("a"), 6, 0), hits)
            assertEquals(listOf("/coffee.md"), hits.map { it.path })
            assertTrue(hits.none { it.content == "喜欢喝拿铁" })
        }
    }

    @Test fun `configuration change during query falls back to fresh lexical results`() = runBlocking {
        val initial = MemoryEmbeddingSettings(enabled = true)
        for (changed in listOf(
            initial.copy(model = "replacement-model"),
            initial.copy(apiKey = "replacement-key"),
            initial.copy(enabled = false),
        )) {
            val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
            indexed(dao, repo)
            repo.writeFile("a", "/coffee.md", "以前喜欢咖啡")
            var live = initial
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
                override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                    assertEquals(initial, config)
                    started.complete(Unit)
                    release.await()
                    return listOf(floatArrayOf(1f, 0f))
                }
            }) { live }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { recall.recall("a", "咖啡", 6, 0) }
            try {
                assertTrue(started.isCompleted)
                assertFalse(pending.isCompleted)
                live = changed
                repo.writeFile("a", "/coffee.md", "咖啡只在早餐喝")
            } finally {
                release.complete(Unit)
            }

            val hits = pending.await()
            assertEquals(recallLocalMemories("咖啡", repo.getActiveFiles("a"), 6, 0), hits)
            assertEquals("咖啡只在早餐喝", hits.single().content)
            assertEquals("/coffee.md", hits.single().path)
        }
    }

    @Test fun `memory deactivated while query request in flight cannot return`() = runBlocking {
        val dao = FakeMemoryDAO(); val repo = MemoryRepository(dao)
        indexed(dao, repo)
        val recall = HybridMemoryRecall(repo, object : MemoryEmbedder {
            override suspend fun embed(config: MemoryEmbeddingSettings, inputs: List<String>): List<FloatArray> {
                repo.setLifecycle("a", "/drink.md", MemoryLifecycle.ARCHIVED)
                return listOf(floatArrayOf(1f, 0f))
            }
        }) { MemoryEmbeddingSettings(enabled = true) }
        assertTrue(recall.recall("a", "咖啡", 6, 0).isEmpty())
    }
}
