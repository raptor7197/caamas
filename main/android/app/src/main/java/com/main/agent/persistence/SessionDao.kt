package com.main.agent.persistence

import androidx.room.*
import com.main.agent.persistence.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun allSessions(): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET title=:title, updatedAt=:now WHERE id=:id")
    suspend fun updateTitle(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM sessions WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM sessions WHERE updatedAt < :cutoffMs")
    suspend fun idsOlderThan(cutoffMs: Long): List<Long>

    @Query("DELETE FROM sessions WHERE updatedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
