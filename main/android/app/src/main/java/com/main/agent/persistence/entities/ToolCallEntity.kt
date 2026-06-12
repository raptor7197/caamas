package com.main.agent.persistence.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_calls",
    foreignKeys = [ForeignKey(
        entity        = MessageEntity::class,
        parentColumns = ["id"],
        childColumns  = ["messageId"],
        onDelete      = ForeignKey.CASCADE,
    )],
    indices = [Index("messageId"), Index("sessionId")],
)
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId:   Long,
    val messageId:   Long,
    val toolName:    String,
    val argsJson:    String,
    val resultJson:  String,
    val success:     Boolean,
    val durationMs:  Long,
    val timestamp:   Long = System.currentTimeMillis(),
)
