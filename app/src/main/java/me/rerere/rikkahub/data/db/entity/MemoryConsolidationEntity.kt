package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * 一个助手在一个对话的记忆整理进度：[consolidatedAt] 及之前的消息已经整理进该助手的记忆库。
 *
 * 单独成表而不是放在 ConversationEntity 上：对话每次保存都会整行重写，会把进度冲掉。
 */
@Entity(
    tableName = "memory_consolidation",
    primaryKeys = ["conversation_id", "assistant_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
)
data class MemoryConsolidationEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
    @ColumnInfo("assistant_id")
    val assistantId: String,
)
