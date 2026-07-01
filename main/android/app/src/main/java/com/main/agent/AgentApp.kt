package com.main.agent

import android.app.Application
import android.os.Process
import android.util.Log
import com.main.agent.persistence.AppDatabase
import com.main.agent.preferences.UserPreferences
import okhttp3.OkHttpClient
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class AgentApp : Application() {
    lateinit var db:         AppDatabase      private set
    lateinit var prefs:      UserPreferences  private set
    lateinit var httpClient: OkHttpClient     private set

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = "Time: ${Date()}\nThread: ${thread.name}\n${Log.getStackTraceString(throwable)}"
                File(filesDir, "crash_log.txt").writeText(report)
            } catch (e: Exception) {
                // best-effort logging only; must not block crash propagation below
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        db    = AppDatabase.get(this)
        prefs = UserPreferences(this)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        instance = this
    }

    fun readLastCrashLog(): String? =
        File(filesDir, "crash_log.txt").let { if (it.exists()) it.readText() else null }

    companion object {
        lateinit var instance: AgentApp private set
    }
}
