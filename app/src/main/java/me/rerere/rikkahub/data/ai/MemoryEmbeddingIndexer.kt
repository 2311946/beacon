package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.memoryContentHash

/** 网络阶段不持有 Room 事务或记忆写锁；最终发布复用 revision/hash/job 的事务校验。 */
internal class MemoryEmbeddingIndexer(
    private val dao: MemoryDAO,
    private val embedder: MemoryEmbedder,
    private val config: () -> MemoryEmbeddingSettings,
) {
    suspend fun index(job: MemoryIndexJobEntity): Boolean {
        val initial = config()
        if (!initial.enabled || !validEmbeddingEndpoint(initial.endpoint) || initial.model.isBlank()) return false
        val file = dao.getActiveFile(job.assistantId, job.path) ?: return false
        if (memoryContentHash(file.content) != job.contentHash ||
            dao.getMetadata(job.assistantId, job.path)?.revision != job.memoryRevision) return false
        val chunks = memoryRecallChunks(MemoryFile(file.path, file.content, file.updatedAt).body)
        if (chunks.isEmpty()) return dao.publishChunks(job, emptyList())
        val entities = mutableListOf<MemoryChunkEntity>()
        try {
            for (batch in chunks.withIndex().toList().chunked(16)) {
                if (config() != initial) return false
                // 发送下一批前再检查授权及生命周期；在途请求无法撤回。
                if (dao.getMetadata(job.assistantId, job.path)?.let {
                        it.lifecycle == "ACTIVE" && it.revision == job.memoryRevision
                    } != true) return false
                // 元数据读取可挂起；必须在它之后再次检查，避免关闭/切换后才外发旧配置。
                if (config() != initial) return false
                val vectors = embedder.embed(initial, batch.map { it.value.content })
                require(vectors.size == batch.size)
                batch.zip(vectors).forEach { (chunk, vector) ->
                    entities += MemoryChunkEntity(
                        assistantId = job.assistantId, path = job.path, chunkIndex = chunk.index,
                        memoryRevision = job.memoryRevision, contentHash = job.contentHash,
                        chunkerVersion = 1, content = chunk.value.content,
                        embedding = MemoryVectorCodec.encode(vector), embeddingProfile = embeddingProfile(initial),
                        embeddingDimensions = vector.size,
                    )
                }
            }
            if (config() != initial) return false
            require(entities.map { it.embeddingDimensions }.distinct().size <= 1)
            val published = dao.publishChunks(job, entities)
            // 配置改变与事务发布之间仍可能有极短窗口：保留新目标的补建意图。
            if (published && config() != initial) {
                dao.ensureIndexJob(job.assistantId, job.path)
                return false
            }
            return published
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 不记录密钥、正文、查询或服务商响应；保留 job 供 WorkManager 退避重试。
            return false
        }
    }
}
