package com.main.agent

import android.app.Application
import com.main.agent.persistence.AppDatabase
import com.main.agent.preferences.UserPreferences

class AgentApp : Application() {
    lateinit var db:    AppDatabase      private set
    lateinit var prefs: UserPreferences  private set

    override fun onCreate() {
        super.onCreate()
        db    = AppDatabase.get(this)
        prefs = UserPreferences(this)
        instance = this
    }

    companion object {
        lateinit var instance: AgentApp private set
    }
}
