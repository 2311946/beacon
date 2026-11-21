package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 文件级保守元数据；不是逐句事实或修订历史。revision每次语义/策略变更重新生成，防删除重建ABA。 */
@Entity(
    tableName = "memory_metadata",
    primaryKeys = ["assistant_id", "path"],
    foreignKeys = [ForeignKey(
        entity = MemoryFileEntity::class,
        parentColumns = ["assistant_id", "path"],
        childColumns = ["assistant_id", "path"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MemoryMetadataEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    val path: String,
    @ColumnInfo("scope_kind") val scopeKind: String,
    val category: String = "UNKNOWN",
    val lifecycle: String = "ACTIVE",
    @ColumnInfo("content_hash") val contentHash: String,
    val revision: String,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "memory_source",
    primaryKeys = ["assistant_id", "path", "source_id"],
    foreignKeys = [ForeignKey(
        entity = MemoryMetadataEntity::class,
        parentColumns = ["assistant_id", "path"],
        childColumns = ["assistant_id", "path"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["assistant_id", "conversation_id", "message_id"])],
)
data class MemorySourceEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    val path: String,
    @ColumnInfo("source_id") val sourceId: String,
    val kind: String,
    val status: String = "CURRENT",
    @ColumnInfo("memory_revision") val memoryRevision: String,
    @ColumnInfo("conversation_id") val conversationId: String? = null,
    @ColumnInfo("message_id") val messageId: String? = null,
    @ColumnInfo("source_json") val sourceJson: String,
)

/** 2A仅建底座，不生成分块；未来worker必须用revision和hash校验后提交。 */
@Entity(
    tableName = "memory_chunk",
    primaryKeys = ["assistant_id", "path", "chunk_index"],
    foreignKeys = [ForeignKey(
        entity = MemoryMetadataEntity::class,
        parentColumns = ["assistant_id", "path"],
        childColumns = ["assistant_id", "path"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MemoryChunkEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    val path: String,
    @ColumnInfo("chunk_index") val chunkIndex: Int,
    @ColumnInfo("memory_revision") val memoryRevision: String,
    @ColumnInfo("content_hash") val contentHash: String,
    @ColumnInfo("chunker_version") val chunkerVersion: Int,
    val content: String,
)

/** 持久化待索引意图，一文件一个任务；这不是已启动的后台worker或已就绪索引。 */
@Entity(
    tableName = "memory_index_job",
    primaryKeys = ["assistant_id", "path"],
    foreignKeys = [ForeignKey(
        entity = MemoryMetadataEntity::class,
        parentColumns = ["assistant_id", "path"],
        childColumns = ["assistant_id", "path"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MemoryIndexJobEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    val path: String,
    @ColumnInfo("memory_revision") val memoryRevision: String,
    @ColumnInfo("content_hash") val contentHash: String,
    @ColumnInfo("queued_at") val queuedAt: Long,
)
