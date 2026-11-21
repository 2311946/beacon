package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity

internal class FakeMemoryDAO : MemoryDAO() {
    private val files = MutableStateFlow<List<MemoryFileEntity>>(emptyList())
    private val metadata = mutableMapOf<Pair<String, String>, MemoryMetadataEntity>()
    private val sources = mutableListOf<MemorySourceEntity>()
    private val chunks = mutableListOf<MemoryChunkEntity>()
    private val jobs = mutableMapOf<Pair<String, String>, MemoryIndexJobEntity>()

    override suspend fun getMetadata(assistantId: String, path: String) = metadata[assistantId to path]
    override suspend fun getSources(assistantId: String, path: String) =
        sources.filter { it.assistantId == assistantId && it.path == path }.sortedBy { it.sourceId }
    override suspend fun getChunks(assistantId: String, path: String) =
        chunks.filter { it.assistantId == assistantId && it.path == path }.sortedBy { it.chunkIndex }
    override suspend fun getIndexJobs(assistantId: String) = jobs.values.filter { it.assistantId == assistantId }
        .sortedWith(compareBy({ it.queuedAt }, { it.path }))
    override suspend fun upsertMetadata(metadata: MemoryMetadataEntity) {
        this.metadata[metadata.assistantId to metadata.path] = metadata
    }
    override suspend fun upsertSources(sources: List<MemorySourceEntity>) {
        sources.forEach { source ->
            this.sources.removeAll { it.assistantId == source.assistantId && it.path == source.path && it.sourceId == source.sourceId }
            this.sources += source
        }
    }
    override suspend fun upsertChunks(chunks: List<MemoryChunkEntity>) {
        chunks.forEach { chunk ->
            this.chunks.removeAll { it.assistantId == chunk.assistantId && it.path == chunk.path && it.chunkIndex == chunk.chunkIndex }
            this.chunks += chunk
        }
    }
    override suspend fun upsertIndexJob(job: MemoryIndexJobEntity) { jobs[job.assistantId to job.path] = job }
    override suspend fun deleteSources(assistantId: String, path: String) {
        sources.removeAll { it.assistantId == assistantId && it.path == path }
    }
    override suspend fun deleteChunks(assistantId: String, path: String) {
        chunks.removeAll { it.assistantId == assistantId && it.path == path }
    }
    override suspend fun deleteIndexJob(assistantId: String, path: String) { jobs.remove(assistantId to path) }

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        files.map { all -> all.filter { it.assistantId == assistantId }.sortedBy { it.path } }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> =
        files.value.filter { it.assistantId == assistantId }.sortedBy { it.path }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? =
        files.value.find { it.assistantId == assistantId && it.path == path }

    override suspend fun getActiveFiles(assistantId: String): List<MemoryFileEntity> =
        getFilesOfAssistant(assistantId).filter { getMetadata(assistantId, it.path)?.lifecycle?.let { state -> state == "ACTIVE" } ?: true }

    override suspend fun getActiveFile(assistantId: String, path: String): MemoryFileEntity? =
        getFile(assistantId, path)?.takeIf { getMetadata(assistantId, path)?.lifecycle?.let { state -> state == "ACTIVE" } ?: true }

    override suspend fun upsertFile(file: MemoryFileEntity) = upsertFiles(listOf(file))

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        val replaced = files.map { it.assistantId to it.path }.toSet()
        this.files.value = this.files.value.filterNot { (it.assistantId to it.path) in replaced } + files
    }

    override suspend fun deleteFile(assistantId: String, path: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId && it.path == path }
        metadata.remove(assistantId to path)
        deleteSources(assistantId, path)
        deleteChunks(assistantId, path)
        deleteIndexJob(assistantId, path)
    }

    override suspend fun deleteFilesOfAssistant(assistantId: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId }
        metadata.keys.filter { it.first == assistantId }.toList().forEach { key ->
            metadata.remove(key)
            deleteSources(key.first, key.second)
            deleteChunks(key.first, key.second)
            deleteIndexJob(key.first, key.second)
        }
    }
}
