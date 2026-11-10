package me.rerere.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
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
