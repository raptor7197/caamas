package com.main.agent.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.main.agent.persistence.AppDatabase

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            SessionManager(AppDatabase.get(applicationContext)).pruneOldSessions()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
