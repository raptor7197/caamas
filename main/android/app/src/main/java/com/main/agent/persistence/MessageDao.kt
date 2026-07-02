package com.main.agent.persistence

import androidx.room.*
import com.main.agent.persistence.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId=:sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId=:sessionId ORDER BY timestamp ASC")
    fun getBySessionFlow(sessionId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId=:sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT content FROM messages WHERE sessionId=:sessionId AND role='user' ORDER BY timestamp ASC LIMIT 1")
    suspend fun firstUserMessage(sessionId: Long): String?
}
