package me.rerere.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ActivityDayCount
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity
import kotlin.uuid.Uuid

class AutonomousActivityRepository(
    private val database: AppDatabase,
) {
    suspend fun record(
        assistantId: Uuid,
        assistantName: String,
        conversationId: Uuid,
        content: String,
        decision: String,
    ) {
        database.autonomousActivityDao().insert(
            AutonomousActivityEntity(
                assistantId = assistantId.toString(),
                assistantName = assistantName,
                conversationId = conversationId.toString(),
                createdAt = System.currentTimeMillis(),
                content = content,
                decision = decision,
            )
        )
    }

    /** 普通角色活动没有会话时用空关联，不创建虚假的聊天会话。 */
    suspend fun recordStatus(
        assistantId: Uuid,
        assistantName: String,
        content: String,
        source: String,
        createdAt: Long,
        conversationId: Uuid? = null,
        messageId: Uuid? = null,
    ) {
        if (content.isBlank()) return
        database.autonomousActivityDao().insert(
            AutonomousActivityEntity(
                id = messageId?.let { "status:$assistantId:$it" }
                    ?: Uuid.random().toString(),
                assistantId = assistantId.toString(),
                assistantName = assistantName,
                conversationId = conversationId?.toString().orEmpty(),
                createdAt = createdAt,
                content = content,
                decision = source,
            )
        )
    }

    /** 只读最新记录，群聊展示不触发生成，也不改写角色状态。 */
    fun observeLatestOrdinaryActivities(assistantIds: List<Uuid>): Flow<Map<Uuid, AutonomousActivityEntity>> {
        val ids = assistantIds.distinct().associateBy { it.toString() }
        if (ids.isEmpty()) return flowOf(emptyMap())
        return database.autonomousActivityDao().observeLatestOrdinaryActivities(ids.keys.toList()).map { activities ->
            activities.mapNotNull { activity ->
                ids[activity.assistantId]?.let { it to activity }
            }.toMap()
        }
    }

    fun observeRange(
        assistantId: Uuid,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<AutonomousActivityEntity>> = database.autonomousActivityDao().observeRange(
        assistantId = assistantId.toString(),
        startInclusive = startInclusive,
        endExclusive = endExclusive,
    )

    fun observeDayCounts(assistantId: Uuid): Flow<List<ActivityDayCount>> =
        database.autonomousActivityDao().observeDayCounts(assistantId.toString())

    fun observeActivities(assistantId: Uuid): Flow<PagingData<AutonomousActivityEntity>> = Pager(
        config = PagingConfig(pageSize = 30),
        pagingSourceFactory = { database.autonomousActivityDao().pagingSource(assistantId.toString()) }
    ).flow
}
