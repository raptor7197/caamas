package com.main.agent.agent

import com.main.agent.persistence.AppDatabase
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.persistence.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

class SessionManager(private val db: AppDatabase) {

    val allSessions: Flow<List<SessionEntity>> = db.sessionDao().allSessions()

    suspend fun newSession(title: String = "New chat"): Long =
        db.sessionDao().insert(SessionEntity(title = title, createdAt = System.currentTimeMillis()))

    suspend fun renameSession(id: Long, title: String) =
        db.sessionDao().updateTitle(id, title)

    suspend fun deleteSession(id: Long) {
        db.messageDao().deleteBySession(id)
        db.toolCallDao().deleteBySession(id)
        db.sessionDao().delete(id)
    }

    suspend fun pruneOldSessions(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        db.sessionDao().idsOlderThan(cutoff).forEach { deleteSession(it) }
    }

    suspend fun clearAllHistory() {
        db.messageDao().deleteAll()
        db.toolCallDao().deleteAll()
        db.sessionDao().deleteAll()
    }

    suspend fun saveMessage(sessionId: Long, role: String, content: String): Long =
        db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                role      = role,
                content   = content,
                timestamp = System.currentTimeMillis(),
            )
        )

    suspend fun getHistory(sessionId: Long): List<Pair<String, String>> =
        db.messageDao().getBySession(sessionId).map { it.role to it.content }

    fun messagesFlow(sessionId: Long): Flow<List<MessageEntity>> =
        db.messageDao().getBySessionFlow(sessionId)
}
