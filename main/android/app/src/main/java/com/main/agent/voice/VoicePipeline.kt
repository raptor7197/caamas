package com.main.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.main.agent.agent.AgentCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "VoicePipeline"

private const val SAMPLE_RATE     = 16000
private const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_DURATION_MS = 30_000
private const val MAX_SAMPLES    = SAMPLE_RATE * MAX_DURATION_MS / 1000

class VoicePipeline(
    private val context:   Context,
    private val stt:      WhisperSTT,
    private val tts:       TTSEngine,
    private val agentCore: AgentCore,
    private val scope:     CoroutineScope,
) {
    sealed class State {
        object Idle       : State()
        object Listening  : State()
        object Processing : State()
        data class Speaking(val text: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val pcmBuffer = ShortArray(MAX_SAMPLES)
    @Volatile private var recordLen = 0
    @Volatile private var recording = false

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.Error("Microphone permission not granted")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            _state.value = State.Error("Cannot initialize AudioRecord")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBuf, SAMPLE_RATE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _state.value = State.Error("AudioRecord failed to initialize")
                return
            }

            recordLen = 0
            recording = true
            audioRecord?.startRecording()
            _state.value = State.Listening

            recordJob = scope.launch(Dispatchers.IO) {
                val buf = ShortArray(minBuf / 2)
                while (recording && recordLen < MAX_SAMPLES) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (n > 0 && recordLen + n <= MAX_SAMPLES) {
                        System.arraycopy(buf, 0, pcmBuffer, recordLen, n)
                        recordLen += n
                    }
                }
            }

            Log.d(TAG, "Recording started: ${SAMPLE_RATE}Hz mono 16bit")
        } catch (e: SecurityException) {
            _state.value = State.Error("Microphone permission denied: ${e.message}")
        } catch (e: Exception) {
            _state.value = State.Error("Failed to start recording: ${e.message}")
        }
    }

    fun stopListening(): String? {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordJob?.cancel()
        recordJob = null

        _state.value = State.Idle

        if (recordLen == 0) {
            Log.d(TAG, "No audio recorded")
            return null
        }

        val pcmFloat = FloatArray(recordLen)
        for (i in 0 until recordLen) {
            pcmFloat[i] = pcmBuffer[i].toFloat() / Short.MAX_VALUE
        }
        recordLen = 0

        val text = stt.transcribe(pcmFloat, SAMPLE_RATE)
        Log.d(TAG, "Transcription: ${text.take(80)}")
        return text.ifBlank { null }
    }

    fun processText(
        text:    String,
        history: List<Pair<String, String>> = emptyList(),
        onToken: (String) -> Unit,
        onDone:  () -> Unit,
    ) {
        scope.launch(Dispatchers.Main) {
            _state.value = State.Processing
            val sb = StringBuilder()
            try {
                agentCore.respond(text, history).collect { token ->
                    sb.append(token)
                    onToken(token)
                }
                val response = sb.toString()
                _state.value = State.Speaking(response)
                tts.speak(response)
                onDone()
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error: ${e.message}", e)
                _state.value = State.Error(e.message ?: "Unknown error")
                onDone()
            } finally {
                _state.value = State.Idle
            }
        }
    }

    fun cancel() {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordJob?.cancel()
        recordJob = null
        tts.stop()
        _state.value = State.Idle
    }
}
