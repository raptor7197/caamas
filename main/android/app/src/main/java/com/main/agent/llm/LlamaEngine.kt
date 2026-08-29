package com.main.agent.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LlamaEngine"

/**
 * Wraps the llama.cpp C++ library via JNI.
 *
 * Usage:
 *   val engine = LlamaEngine()
 *   engine.loadModel(path, capabilityInfo)
 *   engine.inference(prompt).collect { token -> ... }
 *   engine.unload()
 */
class LlamaEngine {

    // ── JNI declarations ────────────────────────────────────────────────────
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, useGpu: Boolean): Long
    private external fun nativeInfer(handle: Long, prompt: String, maxTokens: Int, temperature: Float, cb: InferenceCallback): Boolean
    private external fun nativeCancelInfer(handle: Long)
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeGetVocabSize(handle: Long): Int
    private external fun nativeApplyChatTemplate(handle: Long, messages: Array<String>, addAssistant: Boolean): String

    companion object {
        init {
            try {
                System.loadLibrary("agent_native")
            } catch (e: UnsatisfiedLinkError) {
                // Expected under plain-JVM unit tests (no .so present); any real device
                // ships the library via the APK, so this only ever triggers off-device.
                Log.w(TAG, "agent_native not loadable in this environment: ${e.message}")
            }
        }
    }

    interface InferenceCallback {
        /** Return false to stop generation. */
        fun onToken(token: String): Boolean
        fun onComplete(totalTokens: Int, durationMs: Long)
        fun onError(message: String)
    }

    // ── State ────────────────────────────────────────────────────────────────
    @Volatile private var handle: Long = 0L
    @Volatile private var loaded = false

    // Serializes load/unload against an in-flight inference so the native handle
    // is never freed while nativeInfer() is still using it (use-after-free).
    private val lifecycleMutex = Mutex()

    val isLoaded: Boolean get() = loaded

    // ── Model loading ────────────────────────────────────────────────────────
    suspend fun loadModel(path: String, cap: DeviceCapability.Info): Boolean =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (loaded) unloadLocked()
                Log.i(TAG, "Loading model from $path  ctx=${cap.recommendedCtx}  threads=${cap.recommendedThreads}")
                handle = nativeLoadModel(path, cap.recommendedCtx, cap.recommendedThreads, cap.hasVulkan)
                loaded = handle != 0L
                if (!loaded) Log.e(TAG, "nativeLoadModel returned 0")
                loaded
            }
        }

    // ── Chat template ─────────────────────────────────────────────────────────
    /**
     * Apply the model's built-in Jinja chat template to a list of messages.
     * @param messages Pairs of (role, content).  role = "system"|"user"|"assistant"
     * @param addAssistant Whether to append the assistant turn header (for generation).
     */
    fun applyChatTemplate(
        messages: List<Pair<String, String>>,
        addAssistant: Boolean = true,
    ): String {
        if (!loaded) return ""
        val flat = messages.flatMap { listOf(it.first, it.second) }.toTypedArray()
        return nativeApplyChatTemplate(handle, flat, addAssistant)
    }

    // ── Streaming inference ───────────────────────────────────────────────────
    /**
     * Stream tokens from the model as a [Flow<String>].
     *
     * @param prompt       Already-formatted prompt (use [applyChatTemplate] first).
     * @param maxTokens    Hard cap on generated tokens.
     * @param temperature  0.0 = greedy, 0.7 = balanced, 1.0 = creative.
     */
    fun inference(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = callbackFlow {
        if (!loaded) {
            close(IllegalStateException("Model not loaded"))
            return@callbackFlow
        }

        // Hold the lock for the whole call (through awaitClose) so unload() can't
        // free the handle out from under nativeInfer / the pending cancel signal.
        lifecycleMutex.lock()
        val activeHandle = handle
        if (activeHandle == 0L) {
            lifecycleMutex.unlock()
            close(IllegalStateException("Model not loaded"))
            return@callbackFlow
        }

        val callback = object : InferenceCallback {
            override fun onToken(token: String): Boolean {
                trySend(token)
                return isActive
            }
            override fun onComplete(totalTokens: Int, durationMs: Long) {
                Log.d(TAG, "Inference complete: $totalTokens tokens")
                close()
            }
            override fun onError(message: String) {
                Log.e(TAG, "Inference error: $message")
                close(RuntimeException(message))
            }
        }

        try {
            withContext(Dispatchers.IO) {
                nativeInfer(activeHandle, prompt, maxTokens, temperature, callback)
            }
            // Runs as soon as collection is cancelled or the channel closes above —
            // the native loop polls this flag between tokens (and between prefill batches).
            awaitClose { nativeCancelInfer(activeHandle) }
        } finally {
            lifecycleMutex.unlock()
        }
    }.buffer(Channel.UNLIMITED) // never silently drop tokens when the collector is slower than generation

    /** Cancel any in-progress inference immediately (best-effort — see [handle]). */
    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancelInfer(h)
    }

    fun vocabSize(): Int = if (loaded) nativeGetVocabSize(handle) else 0

    // ── Cleanup ───────────────────────────────────────────────────────────────
    suspend fun unload() = lifecycleMutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (handle != 0L) {
            Log.i(TAG, "Unloading model")
            nativeFreeModel(handle)
            handle = 0L
            loaded = false
        }
    }
}
