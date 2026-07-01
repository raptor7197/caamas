package com.main.agent.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.persistence.entities.SessionEntity
import com.main.agent.persistence.entities.ToolCallEntity
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities  = [SessionEntity::class, MessageEntity::class, ToolCallEntity::class],
    version   = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao():  SessionDao
    abstract fun messageDao():  MessageDao
    abstract fun toolCallDao(): ToolCallDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val enableForeignKeysCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    SQLiteDatabase.loadLibs(appContext)
                    val passphrase = DbPassphraseProvider.getOrCreatePassphrase(appContext)
                    val factory = SupportFactory(passphrase)

                    Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        "agent_db",
                    )
                        .openHelperFactory(factory)
                        .addCallback(enableForeignKeysCallback)
                        .build()
                        .also { INSTANCE = it }
                }
            }
    }
}
