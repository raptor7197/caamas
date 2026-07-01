package com.main.agent.persistence

import androidx.room.*
import com.main.agent.persistence.entities.ToolCallEntity

@Dao
interface ToolCallDao {
    @Insert
    suspend fun insert(call: ToolCallEntity): Long

    @Query("SELECT * FROM tool_calls WHERE sessionId=:sessionId ORDER BY timestamp DESC")
    suspend fun getBySession(sessionId: Long): List<ToolCallEntity>

    @Query("DELETE FROM tool_calls WHERE sessionId=:sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM tool_calls")
    suspend fun deleteAll()
}
