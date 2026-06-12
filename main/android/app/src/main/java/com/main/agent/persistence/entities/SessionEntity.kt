package com.main.agent.persistence.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title:     String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val modelUsed: String = "",
)
