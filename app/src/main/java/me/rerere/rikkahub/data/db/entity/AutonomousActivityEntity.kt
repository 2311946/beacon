package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(
    tableName = "autonomous_activity",
    indices = [Index(value = ["assistant_id", "created_at"])]
)
data class AutonomousActivityEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("assistant_name")
    val assistantName: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    val content: String,
    val decision: String,
)
