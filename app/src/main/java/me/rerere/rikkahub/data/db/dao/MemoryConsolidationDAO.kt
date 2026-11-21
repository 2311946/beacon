package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity

@Dao
interface MemoryConsolidationDAO {
    @Query(
        "SELECT consolidated_at FROM memory_consolidation " +
            "WHERE conversation_id = :conversationId AND assistant_id = :assistantId"
    )
    suspend fun getConsolidatedAt(conversationId: String, assistantId: String): Long?

    @Upsert
    suspend fun upsert(entity: MemoryConsolidationEntity)

    /** 仅返回当前助手的候选对话；群成员授权及是否有新轮次由调用方最终核验。 */
    @Query(
        "SELECT c.id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.assistant_id = :assistantId " +
            "WHERE (c.assistant_id = :assistantId OR c.id IN (:groupConversationIds)) " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0) " +
            "ORDER BY c.update_at DESC, c.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getUpdatedConversationIdsForAssistant(
        assistantId: String,
        groupConversationIds: List<String>,
        since: Long,
        limit: Int,
        offset: Int = 0,
    ): List<String>

    /** 上次整理之后又有更新的对话，最近更新的在前；是否真有新的轮次要读出消息才知道 */
    @Query(
        "SELECT c.id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.assistant_id = c.assistant_id " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0) " +
            "ORDER BY c.update_at DESC LIMIT :limit"
    )
    suspend fun getUpdatedConversationIds(assistantIds: List<String>, since: Long, limit: Int): List<String>

    /** [assistantIds] 里有哪些助手存在上次整理之后又有更新的对话 */
    @Query(
        "SELECT DISTINCT c.assistant_id FROM conversationentity AS c " +
            "LEFT JOIN memory_consolidation AS m ON m.conversation_id = c.id AND m.assistant_id = c.assistant_id " +
            "WHERE c.assistant_id IN (:assistantIds) " +
            "AND c.update_at >= :since " +
            "AND c.update_at > COALESCE(m.consolidated_at, 0)"
    )
    suspend fun getAssistantIdsWithUpdatedConversations(assistantIds: List<String>, since: Long): List<String>
}
