package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MemoryEmbeddingSettings
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.math.pow

/** 失败只影响语义召回；第三批的筛选、预算及稳定前缀仍是底座。 */
class HybridMemoryRecall internal constructor(
    private val repository: MemoryRepository,
    private val embedder: MemoryEmbedder,
    private val currentConfig: () -> MemoryEmbeddingSettings,
) {
    internal constructor(repository: MemoryRepository, embedder: MemoryEmbedder, store: SettingsStore) :
        this(repository, embedder, { store.settingsFlow.value.memoryEmbedding })

    internal suspend fun recall(namespace: String, query: String, topK: Int, nowMillis: Long): List<MemoryRecallHit> {
        if (query.isBlank()) return emptyList()
        val files = repository.getActiveFiles(namespace)
        val lexical = localMemoryRecallCandidates(query, files, nowMillis)
        val fallback = selectMemoryRecallHits(lexical, topK)
        val config = currentConfig()
        if (!config.enabled || !config.semanticWeight.isFinite() || config.semanticWeight <= 0.0 ||
            !validEmbeddingEndpoint(config.endpoint) || config.model.isBlank()) return fallback
        return try {
            withTimeoutOrNull(2500) {
                val profile = embeddingProfile(config)
                // 没有就绪索引时不额外调用 API；后台独立补建。
                val indexed = files.flatMap { file ->
                    repository.indexedChunks(namespace, file.path).filter {
                        it.embeddingProfile == profile && it.chunkerVersion == 1 &&
                            it.contentHash == memoryContentHash(file.content) && it.embedding != null
                    }.map { file to it }
                }
                if (indexed.isEmpty()) return@withTimeoutOrNull freshLexical(namespace, query, topK, nowMillis)
                val boundedQuery = query.substring(0, query.offsetByCodePoints(0, query.codePointCount(0, query.length).coerceAtMost(2048)))
                // DB 遍历可挂起；外发前重查开关和端点，不能在撤销后才发送旧配置查询。
                if (currentConfig() != config) return@withTimeoutOrNull freshLexical(namespace, query, topK, nowMillis)
                val vector = embedder.embed(config, listOf(boundedQuery)).single()
                if (currentConfig() != config) return@withTimeoutOrNull freshLexical(namespace, query, topK, nowMillis)
                val semantic = indexed.mapNotNull { (file, chunk) ->
                    val decoded = MemoryVectorCodec.decode(checkNotNull(chunk.embedding), chunk.embeddingDimensions) ?: return@mapNotNull null
                    val cosine = MemoryVectorCodec.cosine(vector, decoded) ?: return@mapNotNull null
                    if (cosine <= 0.0) return@mapNotNull null
                    MemoryRecallHit(file.path, file.version, chunk.chunkIndex, chunk.content, file.updatedAt, cosine)
                }
                // 网络等待后重查 ACTIVE 与完整正文，删除/停用/编辑不能借旧快照复活。
                val originalChunks = indexed.associate { (file, chunk) -> (file.path to chunk.chunkIndex) to chunk }
                val currentChunks = semantic.map { it.path }.distinct().associateWith { path ->
                    repository.indexedChunks(namespace, path).associateBy { it.chunkIndex }
                }
                val latest = repository.getActiveFiles(namespace).associateBy { it.path }
                val valid: (MemoryRecallHit) -> Boolean = { hit ->
                    val live = latest[hit.path]
                    val original = files.firstOrNull { it.path == hit.path }
                    live != null && original != null && live.content == original.content && live.updatedAt == original.updatedAt
                }
                val currentSemantic = semantic.filter(valid).filter { hit ->
                    val original = originalChunks[hit.path to hit.chunkIndex]
                    val current = currentChunks[hit.path]?.get(hit.chunkIndex)
                    original != null && current != null && current.memoryRevision == original.memoryRevision &&
                        current.embeddingProfile == profile && current.content == hit.content
                }
                if (currentConfig() != config) return@withTimeoutOrNull freshLexical(namespace, query, topK, nowMillis)
                mixMemoryRecall(localMemoryRecallCandidates(query, latest.values.toList(), nowMillis), currentSemantic, config.semanticWeight, topK, nowMillis)
            } ?: freshLexical(namespace, query, topK, nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            freshLexical(namespace, query, topK, nowMillis)
        }
    }

    private suspend fun freshLexical(namespace: String, query: String, topK: Int, now: Long) =
        recallLocalMemories(query, repository.getActiveFiles(namespace), topK, now)
}

/** 词法先去掉已有时间因子并按本轮最大值归一；余弦截在0..1，最后统一乘时间衰减。 */
internal fun mixMemoryRecall(
    lexical: List<MemoryRecallHit>, semantic: List<MemoryRecallHit>, weight: Double, topK: Int, now: Long,
): List<MemoryRecallHit> {
    if (!weight.isFinite() || weight <= 0 || semantic.isEmpty()) return selectMemoryRecallHits(lexical, topK)
    fun decay(hit: MemoryRecallHit): Double = 0.5 + 0.5 * 2.0.pow(-((now.toDouble() - hit.updatedAt).coerceAtLeast(0.0) / 86_400_000.0) / 90.0)
    fun key(hit: MemoryRecallHit) = Triple(hit.path, hit.version, hit.chunkIndex)
    val lexicalScores = lexical.associate { key(it) to it.score / decay(it) }
    val maximum = lexicalScores.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val semanticScores = semantic.associate { key(it) to it.score.coerceIn(0.0, 1.0) }
    val w = weight.coerceIn(0.0, 1.0)
    return selectMemoryRecallHits((lexical + semantic).distinctBy(::key).mapNotNull { hit ->
        val score = ((1 - w) * (lexicalScores[key(hit)] ?: 0.0) / maximum + w * (semanticScores[key(hit)] ?: 0.0)) * decay(hit)
        if (score > 0 && score.isFinite()) hit.copy(score = score) else null
    }, topK)
}
