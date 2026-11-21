package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryChunkEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryIndexJobEntity
import me.rerere.rikkahub.data.db.entity.MemoryMetadataEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity

@Dao
abstract class MemoryDAO {
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    abstract fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    abstract suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun getFile(assistantId: String, path: String): MemoryFileEntity?

    @Query("SELECT f.* FROM memory_file f LEFT JOIN memory_metadata m ON f.assistant_id = m.assistant_id AND f.path = m.path WHERE f.assistant_id = :assistantId AND (m.lifecycle IS NULL OR m.lifecycle = 'ACTIVE') ORDER BY f.path")
    abstract suspend fun getActiveFiles(assistantId: String): List<MemoryFileEntity>

    @Query("SELECT f.* FROM memory_file f LEFT JOIN memory_metadata m ON f.assistant_id = m.assistant_id AND f.path = m.path WHERE f.assistant_id = :assistantId AND f.path = :path AND (m.lifecycle IS NULL OR m.lifecycle = 'ACTIVE')")
    abstract suspend fun getActiveFile(assistantId: String, path: String): MemoryFileEntity?

    @Upsert
    abstract suspend fun upsertFile(file: MemoryFileEntity)

    @Upsert
    abstract suspend fun upsertFiles(files: List<MemoryFileEntity>)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun deleteFile(assistantId: String, path: String)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId")
    abstract suspend fun deleteFilesOfAssistant(assistantId: String)

    @Query("SELECT * FROM memory_metadata WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun getMetadata(assistantId: String, path: String): MemoryMetadataEntity?

    @Query("SELECT * FROM memory_source WHERE assistant_id = :assistantId AND path = :path ORDER BY source_id")
    abstract suspend fun getSources(assistantId: String, path: String): List<MemorySourceEntity>

    @Query("SELECT * FROM memory_chunk WHERE assistant_id = :assistantId AND path = :path ORDER BY chunk_index")
    abstract suspend fun getChunks(assistantId: String, path: String): List<MemoryChunkEntity>

    @Query("SELECT * FROM memory_index_job WHERE assistant_id = :assistantId ORDER BY queued_at, path")
    abstract suspend fun getIndexJobs(assistantId: String): List<MemoryIndexJobEntity>

    @Upsert
    abstract suspend fun upsertMetadata(metadata: MemoryMetadataEntity)

    @Upsert
    abstract suspend fun upsertSources(sources: List<MemorySourceEntity>)

    @Upsert
    abstract suspend fun upsertChunks(chunks: List<MemoryChunkEntity>)

    @Upsert
    abstract suspend fun upsertIndexJob(job: MemoryIndexJobEntity)

    @Query("DELETE FROM memory_source WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun deleteSources(assistantId: String, path: String)

    @Query("DELETE FROM memory_chunk WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun deleteChunks(assistantId: String, path: String)

    @Query("DELETE FROM memory_index_job WHERE assistant_id = :assistantId AND path = :path")
    abstract suspend fun deleteIndexJob(assistantId: String, path: String)

    /** 正文、证据、清理旧索引和任务入队是同一事务，不能留下半次写入。 */
    @Transaction
    open suspend fun saveState(
        file: MemoryFileEntity,
        metadata: MemoryMetadataEntity,
        sources: List<MemorySourceEntity>,
    ) {
        require(file.assistantId == metadata.assistantId && file.path == metadata.path)
        require(sources.all { it.assistantId == file.assistantId && it.path == file.path })
        upsertFile(file)
        upsertMetadata(metadata)
        deleteSources(file.assistantId, file.path)
        upsertSources(sources)
        deleteChunks(file.assistantId, file.path)
        deleteIndexJob(file.assistantId, file.path)
        if (metadata.lifecycle == "ACTIVE") {
            upsertIndexJob(MemoryIndexJobEntity(file.assistantId, file.path, metadata.revision, metadata.contentHash, metadata.updatedAt))
        }
    }

    @Transaction
    open suspend fun saveStates(
        files: List<MemoryFileEntity>,
        metadata: List<MemoryMetadataEntity>,
        sources: List<List<MemorySourceEntity>>,
    ) {
        require(files.size == metadata.size && files.size == sources.size)
        files.indices.forEach { saveState(files[it], metadata[it], sources[it]) }
    }

    /** 后续索引器唯一的发布入口：旧任务、跨角色、旧分块版本及删除重建的结果均不可写回。 */
    @Transaction
    open suspend fun publishChunks(job: MemoryIndexJobEntity, chunks: List<MemoryChunkEntity>): Boolean {
        val metadata = getMetadata(job.assistantId, job.path) ?: return false
        val file = getFile(job.assistantId, job.path) ?: return false
        if (metadata.lifecycle != "ACTIVE" || metadata.revision != job.memoryRevision ||
            metadata.contentHash != job.contentHash ||
            me.rerere.rikkahub.data.model.memoryContentHash(file.content) != job.contentHash ||
            getIndexJobs(job.assistantId).none { it == job }
        ) return false
        require(chunks.map { it.chunkIndex } == chunks.indices.toList())
        require(chunks.all {
            it.assistantId == job.assistantId && it.path == job.path &&
                it.memoryRevision == job.memoryRevision && it.contentHash == job.contentHash && it.chunkerVersion > 0
        })
        require(chunks.map { it.chunkerVersion }.distinct().size <= 1)
        deleteChunks(job.assistantId, job.path)
        upsertChunks(chunks)
        deleteIndexJob(job.assistantId, job.path)
        return true
    }
}
