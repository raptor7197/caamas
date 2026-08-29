package com.main.agent.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("user_prefs")

class UserPreferences(context: Context) {

    private val ds = context.applicationContext.dataStore

    data class Settings(
        val agentFolderUri:  String = "",
        val openAIKey:       String = "",
        val anthropicKey:    String = "",
        val mistralKey:      String = "",
        val geminiKey:       String = "",
        val ollamaUrl:       String = "http://192.168.1.100:11434",
        val cloudProvider:   String = "",   // "openai"|"anthropic"|"mistral"|"gemini"|"ollama"|""
        val chunkSize:       Int    = 512,
        val chunkOverlapPct: Int    = 10,
        val onboardingDone:  Boolean = false,
    )

    // API keys are encrypted at rest (Android Keystore AES-GCM) — DataStore's backing file is
    // plaintext-on-disk, and app-internal storage is readable on rooted devices / via adb backup.
    val settingsFlow: Flow<Settings> = ds.data.map { p ->
        Settings(
            agentFolderUri  = p[Keys.FOLDER_URI]     ?: "",
            openAIKey       = SecretCrypto.decrypt(p[Keys.OPENAI_KEY]    ?: ""),
            anthropicKey    = SecretCrypto.decrypt(p[Keys.ANTHROPIC_KEY] ?: ""),
            mistralKey      = SecretCrypto.decrypt(p[Keys.MISTRAL_KEY]   ?: ""),
            geminiKey       = SecretCrypto.decrypt(p[Keys.GEMINI_KEY]    ?: ""),
            ollamaUrl       = p[Keys.OLLAMA_URL]     ?: "http://192.168.1.100:11434",
            cloudProvider   = p[Keys.CLOUD_PROVIDER] ?: "",
            chunkSize       = p[Keys.CHUNK_SIZE]     ?: 512,
            chunkOverlapPct = p[Keys.CHUNK_OVERLAP]  ?: 10,
            onboardingDone  = p[Keys.ONBOARDING]     ?: false,
        )
    }

    suspend fun setAgentFolderUri(uri: String)  = ds.edit { it[Keys.FOLDER_URI]     = uri }
    suspend fun setOpenAIKey(key: String)        = ds.edit { it[Keys.OPENAI_KEY]     = SecretCrypto.encrypt(key) }
    suspend fun setAnthropicKey(key: String)     = ds.edit { it[Keys.ANTHROPIC_KEY]  = SecretCrypto.encrypt(key) }
    suspend fun setMistralKey(key: String)       = ds.edit { it[Keys.MISTRAL_KEY]    = SecretCrypto.encrypt(key) }
    suspend fun setGeminiKey(key: String)        = ds.edit { it[Keys.GEMINI_KEY]     = SecretCrypto.encrypt(key) }
    suspend fun setOllamaUrl(url: String)        = ds.edit { it[Keys.OLLAMA_URL]     = url }
    suspend fun setCloudProvider(p: String)      = ds.edit { it[Keys.CLOUD_PROVIDER] = p }
    suspend fun setChunkSize(n: Int)             = ds.edit { it[Keys.CHUNK_SIZE]     = n }
    suspend fun setChunkOverlap(n: Int)          = ds.edit { it[Keys.CHUNK_OVERLAP]  = n }
    suspend fun setOnboardingDone(done: Boolean) = ds.edit { it[Keys.ONBOARDING]     = done }

    private object Keys {
        val FOLDER_URI     = stringPreferencesKey("agent_folder_uri")
        val OPENAI_KEY     = stringPreferencesKey("openai_key")
        val ANTHROPIC_KEY  = stringPreferencesKey("anthropic_key")
        val MISTRAL_KEY    = stringPreferencesKey("mistral_key")
        val GEMINI_KEY     = stringPreferencesKey("gemini_key")
        val OLLAMA_URL     = stringPreferencesKey("ollama_url")
        val CLOUD_PROVIDER = stringPreferencesKey("cloud_provider")
        val CHUNK_SIZE     = intPreferencesKey("chunk_size")
        val CHUNK_OVERLAP  = intPreferencesKey("chunk_overlap")
        val ONBOARDING     = booleanPreferencesKey("onboarding_done")
    }
}
