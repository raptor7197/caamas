package com.main.agent.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "ModelManager"

class ModelManager(
    private val context: Context,
    private val engine: LlamaEngine,
    private val capability: DeviceCapability.Info,
) {
    sealed class State {
        object Idle : State()
        data class Downloading(val progress: Float, val bytesTotal: Long) : State()
        object Verifying : State()
        object Loading : State()
        object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // App-scoped external storage (Android/data/<package>/files/models) — no permission needed,
    // unlike the public Downloads dir this used to write to (which required the
    // MANAGE_EXTERNAL_STORAGE "all files access" permission, a Play Store policy blocker not
    // justified by this feature). Trades away surviving an uninstall for not needing that grant.
    val modelsDir: File = context.getExternalFilesDir("models")
        ?.also { it.mkdirs() }
        ?: File(context.filesDir, "models").also { it.mkdirs() }

    init {
        Log.i(TAG, "Models dir set to: ${modelsDir.absolutePath}")
    }

    val targetModel: ModelSpec
        get() = when (capability.maxModelTier) {
            DeviceCapability.ModelTier.LARGE -> ModelSpec.LLAMA_3_1_8B
            DeviceCapability.ModelTier.SMALL -> ModelSpec.QWEN_2_5_1_5B
        }

    // Dedupe concurrent downloads of the same destination file (e.g. two callers
    // both racing ensureReady() at startup).
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun ensureReady(): Boolean {
        val spec = targetModel
        val file = File(modelsDir, spec.filename)

        if (!file.exists() || !verifyChecksum(file, spec.sha256, spec.sizeBytes)) {
            _state.value = State.Downloading(0f, spec.sizeBytes)
            val ok = downloadModel(spec, file)
            if (!ok) {
                _state.value = State.Error("Download failed for ${spec.filename}")
                return false
            }
        }

        _state.value = State.Loading
        val loaded = engine.loadModel(file.absolutePath, capability)
        _state.value = if (loaded) State.Ready else State.Error("Failed to load model into engine")
        return loaded
    }

    private suspend fun downloadModel(spec: ModelSpec, dest: File): Boolean {
        val lock = downloadLocks.getOrPut(dest.absolutePath) { Mutex() }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                // Someone else may have finished the download while we waited for the lock.
                if (dest.exists() && verifyChecksum(dest, spec.sha256, spec.sizeBytes)) return@withContext true

                val tmp = File(dest.parent, "${dest.name}.tmp")
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build()

                    val resumeFrom = if (tmp.exists()) tmp.length() else 0L
                    val reqBuilder = Request.Builder().url(spec.downloadUrl)
                    if (resumeFrom > 0) reqBuilder.header("Range", "bytes=$resumeFrom-")
                    val resp = client.newCall(reqBuilder.build()).execute()

                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP ${resp.code} downloading ${spec.filename}")
                        return@withContext false
                    }
                    val resumed = resp.code == 206
                    if (resumeFrom > 0 && !resumed) {
                        // Server ignored the Range request — must restart from scratch.
                        tmp.delete()
                    }
                    val body = resp.body ?: return@withContext false
                    val startAt = if (resumed) resumeFrom else 0L
                    val total   = body.contentLength().let { if (it > 0 && resumed) it + startAt else it }
                    var rx = startAt

                    FileOutputStream(tmp, resumed).use { out ->
                        body.byteStream().use { ins ->
                            val buf = ByteArray(256 * 1024)
                            var n: Int
                            while (ins.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                rx += n
                                _state.value = State.Downloading(
                                    progress = if (total > 0) rx.toFloat() / total else 0f,
                                    bytesTotal = total,
                                )
                            }
                        }
                    }

                    _state.value = State.Verifying
                    if (!verifyChecksum(tmp, spec.sha256, spec.sizeBytes)) {
                        Log.e(TAG, "Checksum/size mismatch for ${spec.filename}")
                        tmp.delete()
                        return@withContext false
                    }

                    if (dest.exists() && !dest.delete()) {
                        Log.e(TAG, "Could not remove stale file at ${dest.absolutePath}")
                        return@withContext false
                    }
                    if (!tmp.renameTo(dest)) {
                        // renameTo can fail silently across filesystems — fall back to copy+delete.
                        try {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to install downloaded model: ${e.message}", e)
                            return@withContext false
                        }
                    }
                    Log.i(TAG, "Model downloaded: ${dest.absolutePath}  (${rx / 1_048_576} MB)")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Download error: ${e.message}", e)
                    false
                }
            }
        }
    }

    /**
     * @param expectedSize When > 0, the file size is checked first and must match within a
     * small tolerance — this is what lets us reject a corrupt/truncated download even for
     * models that ship with a blank [expected] SHA-256 (issue #11). The tolerance absorbs
     * harmless metadata-only re-publishes by upstream quantizers that shift the file by a
     * few KB without changing weights (bartowski frequently does this). Pass -1L only for
     * ad-hoc hash checks where the true size isn't known.
     */
    internal suspend fun verifyChecksum(file: File, expected: String, expectedSize: Long = -1L): Boolean =
        withContext(Dispatchers.IO) {
            if (expectedSize > 0) {
                val actual = file.length()
                val delta  = kotlin.math.abs(actual - expectedSize)
                // 2 MB tolerance — large enough to absorb upstream metadata drift,
                // small enough that a truncated multi-GB download still fails the check.
                val tolerance = 2L * 1024 * 1024
                if (delta > tolerance) {
                    Log.e(TAG, "Size mismatch for ${file.name}: got $actual, expected ~$expectedSize (Δ=$delta)")
                    return@withContext false
                }
                if (delta != 0L) {
                    Log.w(TAG, "Size within tolerance for ${file.name}: got $actual, expected $expectedSize (Δ=$delta)")
                }
            }
            if (expected.isBlank()) {
                Log.w(TAG, "No SHA-256 configured for ${file.name} — ${if (expectedSize > 0) "size within tolerance, accepting" else "skipping integrity check"} (issue #11)")
                return@withContext true
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered(256 * 1024).use { ins ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                (actual == expected).also { ok ->
                    if (!ok) Log.e(TAG, "SHA256 mismatch\n  expected=$expected\n  actual=$actual")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Checksum error: ${e.message}", e)
                false
            }
        }

    data class ModelSpec(
        val name: String,
        val filename: String,
        val downloadUrl: String,
        val sha256: String,
        val sizeBytes: Long,
    ) {
        companion object {
            val LLAMA_3_1_8B = ModelSpec(
                name = "Llama 3.1 8B Instruct Q4_K_M",
                filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                sha256 = "7b064f5842bf9532c91456deda288a1b672397a54fa729aa665952863033557c",
                sizeBytes = 4_920_739_232L,
            )

            val QWEN_2_5_1_5B = ModelSpec(
                name = "Qwen 2.5 1.5B Instruct Q4_K_M",
                filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                sha256 = "",
                sizeBytes = 1_104_000_000L,
            )

            val GEMMA_2_2B = ModelSpec(
                name = "Gemma 2 2B Instruct Q4_K_M",
                filename = "gemma-2-2b-it-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
                sha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
                sizeBytes = 1_708_582_752L,
            )

            val WHISPER_BASE_EN = ModelSpec(
                name = "Whisper base.en",
                filename = "ggml-base.en.bin",
                downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
                sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
                sizeBytes = 147_964_211L,
            )

            val NOMIC_EMBED_TEXT = ModelSpec(
                name = "nomic-embed-text-v1.5 Q4_K_M",
                filename = "nomic-embed-text-v1.5.Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5-Q4_K_M.gguf",
                sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac",
                sizeBytes = 84_106_624L,
            )
        }
    }
}
