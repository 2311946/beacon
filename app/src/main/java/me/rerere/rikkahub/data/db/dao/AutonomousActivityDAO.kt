package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.AutonomousActivityEntity

@Dao
interface AutonomousActivityDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AutonomousActivityEntity)

    @Query("SELECT * FROM autonomous_activity WHERE assistant_id = :assistantId ORDER BY created_at DESC, id DESC")
    fun pagingSource(assistantId: String): PagingSource<Int, AutonomousActivityEntity>
}
