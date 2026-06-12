package com.main.agent.llm.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "OllamaProvider"

/**
 * Ollama self-hosted backend.
 * Default base URL points to a local server (e.g., on the same Wi-Fi network).
 * Users configure this in Settings → Cloud → Ollama URL.
 */
class OllamaProvider(
    private val baseUrl: String = "http://192.168.1.100:11434",
    val model: String = "llama3.1:8b",
) : CloudProvider {

    override val name = "Ollama ($model)"
    override val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    private val client = OkHttpClient.Builder().build()

    override fun complete(
        messages: List<Pair<String, String>>,
        tools: List<String>,
        temperature: Float,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonObject("options") {
                put("temperature", temperature.toDouble())
                put("num_predict", maxTokens)
            }
            putJsonArray("messages") {
                messages.forEach { (role, content) ->
                    addJsonObject { put("role", role); put("content", content) }
                }
            }
        }

        val url = "${baseUrl.trimEnd('/')}/api/chat"
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        val resp = withContext(Dispatchers.IO) {
            try { call.execute() }
            catch (e: IOException) {
                close(IOException("Cannot reach Ollama at $baseUrl. Is the server running?"))
                return@withContext null
            }
        } ?: return@callbackFlow

        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: "HTTP ${resp.code}"
            Log.e(TAG, "API error: $err")
            close(IOException(err)); return@callbackFlow
        }

        resp.body?.source()?.let { src ->
            withContext(Dispatchers.IO) {
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    try {
                        val json = Json.parseToJsonElement(line).jsonObject
                        json["message"]?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                            ?.let { trySend(it) }
                        if (json["done"]?.jsonPrimitive?.boolean == true) break
                    } catch (_: Exception) {}
                }
            }
        }
        close()
        awaitClose { call.cancel() }
    }
}
