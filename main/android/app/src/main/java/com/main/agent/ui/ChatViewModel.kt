package com.main.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.main.agent.agent.AgentCore
import com.main.agent.agent.SessionManager
import com.main.agent.persistence.AppDatabase
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.voice.VoicePipeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages:        List<MessageEntity> = emptyList(),
    val isGenerating:    Boolean             = false,
    val streamingText:   String              = "",
    val sessionId:       Long                = -1L,
    val error:           String?             = null,
    val awaitingConfirm: ConfirmRequest?     = null,
    val voiceState:      VoicePipeline.State = VoicePipeline.State.Idle,
    val modelStats:      ModelStats?         = null,
)

data class ModelStats(
    val modelName: String,
    val contextSize: Int,
    val threads: Int,
    val hasVulkan: Boolean,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
)

data class ConfirmRequest(val prompt: String, val toolName: String, val argsJson: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app

    private val db             = AppDatabase.get(app)
    private val sessionManager = SessionManager(db)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generateJob: Job? = null
    private var _voicePipeline: VoicePipeline? = null

    private var _agentCore: AgentCore? = null
    var agentCore: AgentCore?
        get() = _agentCore
        set(value) {
            _agentCore = value
            if (value != null) {
                if (_uiState.value.sessionId < 0) loadOrCreateSession()
                startStatsRefresh()
            }
        }

    private var statsJob: Job? = null

    private fun startStatsRefresh() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (true) {
                updateStats()
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun updateStats() {
        val core = _agentCore ?: return
        val cap = core.capability
        val am = application.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        _uiState.update {
            it.copy(
                modelStats = ModelStats(
                    modelName = cap.maxModelTier.label,
                    contextSize = cap.recommendedCtx,
                    threads = cap.recommendedThreads,
                    hasVulkan = cap.hasVulkan,
                    ramUsedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
                    ramTotalMb = memInfo.totalMem / (1024 * 1024)
                )
            )
        }
    }

    fun setVoicePipeline(pipeline: VoicePipeline) {
        _voicePipeline = pipeline
        viewModelScope.launch {
            pipeline.state.collect { st ->
                _uiState.update { it.copy(voiceState = st) }
            }
        }
    }

    private var sessionLoaded = false

    fun loadOrCreateSession() {
        if (sessionLoaded) return
        sessionLoaded = true
        viewModelScope.launch {
            val sessionId = sessionManager.newSession()
            _uiState.update { it.copy(sessionId = sessionId) }
            sessionManager.messagesFlow(sessionId).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    private val maxInputLength = 4000

    fun sendMessage(text: String) {
        val core = _agentCore ?: run {
            _uiState.update { it.copy(error = "Model not loaded yet — please wait") }
            return
        }
        val sessionId = _uiState.value.sessionId
        val trimmed = text.trim()
        if (sessionId < 0 || trimmed.isBlank()) return
        if (trimmed.length > maxInputLength) {
            _uiState.update { it.copy(error = "Input too long (max $maxInputLength chars)") }
            return
        }

        generateJob?.cancel()

        generateJob = viewModelScope.launch {
            sessionManager.saveMessage(sessionId, "user", trimmed)

            val history = sessionManager.getHistory(sessionId)
                .filter { it.first != "system" }

            _uiState.update { it.copy(isGenerating = true, streamingText = "", error = null) }

            val sb = StringBuilder()
            try {
                core.respond(trimmed, history).collect { token ->
                    sb.append(token)
                    _uiState.update { it.copy(streamingText = sb.toString()) }
                }
                val full = sb.toString()
                if (full.isNotBlank()) {
                    sessionManager.saveMessage(sessionId, "assistant", full)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Generation error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false, streamingText = "") }
            }
        }
    }

    fun cancelGeneration() {
        generateJob?.cancel()
        _voicePipeline?.cancel()
        _uiState.update { it.copy(isGenerating = false, streamingText = "") }
    }

    fun startVoiceListening() {
        _voicePipeline?.startListening()
    }

    fun stopVoiceListening(): String? {
        return _voicePipeline?.stopListening()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
