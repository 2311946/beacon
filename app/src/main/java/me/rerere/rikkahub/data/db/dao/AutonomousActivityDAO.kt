package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity

@Dao
interface AutonomousActivityDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AutonomousActivityEntity)

    @Query(
        "SELECT * FROM autonomous_activity WHERE assistant_id = :assistantId " +
            "AND created_at >= :startInclusive AND created_at < :endExclusive ORDER BY created_at ASC, id ASC"
    )
    fun observeRange(
        assistantId: String,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<AutonomousActivityEntity>>

    /** 全量统计本地日期的条数，不受明细分页大小限制。 */
    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM autonomous_activity WHERE assistant_id = :assistantId GROUP BY day ORDER BY day DESC"
    )
    fun observeDayCounts(assistantId: String): Flow<List<ActivityDayCount>>

    @Query("SELECT * FROM autonomous_activity WHERE assistant_id = :assistantId ORDER BY created_at DESC, id DESC")
    fun pagingSource(assistantId: String): PagingSource<Int, AutonomousActivityEntity>
}

data class ActivityDayCount(val day: String, val count: Int)
