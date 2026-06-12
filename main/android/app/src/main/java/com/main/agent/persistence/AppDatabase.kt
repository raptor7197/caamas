package com.main.agent.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.persistence.entities.SessionEntity
import com.main.agent.persistence.entities.ToolCallEntity

@Database(
    entities  = [SessionEntity::class, MessageEntity::class, ToolCallEntity::class],
    version   = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao():  SessionDao
    abstract fun messageDao():  MessageDao
    abstract fun toolCallDao(): ToolCallDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agent_db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
