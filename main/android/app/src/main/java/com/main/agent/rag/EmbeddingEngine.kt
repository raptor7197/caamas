package com.main.agent.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "EmbeddingEngine"

private const val EMBED_CTX     = 512
private const val EMBED_THREADS = 2

/**
 * Generates text embeddings via llama.cpp JNI using a dedicated embedding model
 * (nomic-embed-text-v1.5 Q4_K_M, 768-dim).
 */
class EmbeddingEngine {

    companion object {
        init { System.loadLibrary("agent_native") }
    }

    private external fun nativeLoadEmbeddingModel(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeEmbedText(handle: Long, text: String): FloatArray?
    private external fun nativeFreeEmbeddingModel(handle: Long)

    private var handle: Long = 0L
    private var dim = 0

    val embeddingDim: Int get() = dim

    val isLoaded: Boolean get() = handle != 0L

    suspend fun loadModel(path: String): Boolean = withContext(Dispatchers.IO) {
        if (handle != 0L) unload()
        Log.i(TAG, "Loading embedding model from $path")
        handle = nativeLoadEmbeddingModel(path, EMBED_CTX, EMBED_THREADS)
        dim = if (handle != 0L) 768 else 0
        (handle != 0L).also { ok ->
            if (!ok) Log.e(TAG, "nativeLoadEmbeddingModel returned 0")
        }
    }

    fun embed(text: String): FloatArray? {
        if (handle == 0L) return null
        return nativeEmbedText(handle, text)
    }

    fun unload() {
        if (handle != 0L) {
            Log.i(TAG, "Unloading embedding model")
            nativeFreeEmbeddingModel(handle)
            handle = 0L
            dim = 0
        }
    }
}
