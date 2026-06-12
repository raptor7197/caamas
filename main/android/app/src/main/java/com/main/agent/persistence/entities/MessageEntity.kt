package com.main.agent.persistence.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity        = SessionEntity::class,
        parentColumns = ["id"],
        childColumns  = ["sessionId"],
        onDelete      = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId:  Long,
    val role:       String,   // "system" | "user" | "assistant" | "tool"
    val content:    String,
    val timestamp:  Long,
    val tokenCount: Int = 0,
)
