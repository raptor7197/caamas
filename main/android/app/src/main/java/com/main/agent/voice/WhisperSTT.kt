package com.main.agent.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WhisperSTT"

class WhisperSTT(private val context: Context) {

    companion object {
        init { System.loadLibrary("agent_native") }
    }

    private external fun nativeLoadWhisperModel(path: String, nThreads: Int): Long
    private external fun nativeTranscribe(handle: Long, pcmData: FloatArray, sampleRate: Int): String
    private external fun nativeFreeWhisperModel(handle: Long)

    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    suspend fun loadModel(modelPath: String, nThreads: Int = 2): Boolean =
        withContext(Dispatchers.IO) {
            if (handle != 0L) unload()
            Log.i(TAG, "Loading whisper model from $modelPath")
            handle = nativeLoadWhisperModel(modelPath, nThreads)
            (handle != 0L).also { ok ->
                if (!ok) Log.e(TAG, "nativeLoadWhisperModel returned 0")
                else Log.i(TAG, "Whisper model loaded")
            }
        }

    fun transcribe(pcmData: FloatArray, sampleRate: Int = 16000): String {
        if (handle == 0L) return ""
        return nativeTranscribe(handle, pcmData, sampleRate)
    }

    fun unload() {
        if (handle != 0L) {
            Log.i(TAG, "Unloading whisper model")
            nativeFreeWhisperModel(handle)
            handle = 0L
        }
    }
}
