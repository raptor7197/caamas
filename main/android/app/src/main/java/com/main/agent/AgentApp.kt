package com.main.agent

import android.app.Application
import com.main.agent.persistence.AppDatabase
import com.main.agent.preferences.UserPreferences
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentApp : Application() {
    lateinit var db:         AppDatabase      private set
    lateinit var prefs:      UserPreferences  private set
    lateinit var httpClient: OkHttpClient     private set

    override fun onCreate() {
        super.onCreate()
        db    = AppDatabase.get(this)
        prefs = UserPreferences(this)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        instance = this
    }

    companion object {
        lateinit var instance: AgentApp private set
    }
}
