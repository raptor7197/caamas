package com.main.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

private const val TAG = "TTSEngine"

/**
 * Wraps Android's built-in TextToSpeech API.
 * Phase 2 upgrade: swap in Piper ONNX for natural voice quality.
 */
class TTSEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready  = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) Log.w(TAG, "TTS language not supported")
                else Log.i(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /** Speak text. Returns when utterance is complete. */
    suspend fun speak(text: String): Unit = suspendCancellableCoroutine { cont ->
        if (!ready || tts == null) { cont.resume(Unit); return@suspendCancellableCoroutine }

        val id = UUID.randomUUID().toString()
        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?)  { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            // stop() (user-initiated interrupt) fires onStop, not onDone/onError — without this,
            // interrupting speech would leave the suspend call hung forever.
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
        })

        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { tts?.stop() }
    }

    /** Stop any ongoing speech. */
    fun stop() { tts?.stop() }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
