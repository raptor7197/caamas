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

private const val TAG = "MistralProvider"
private const val BASE_URL = "https://api.mistral.ai/v1/chat/completions"

class MistralProvider(
    private val apiKey: String,
    val model: String = "mistral-large-latest",
) : CloudProvider {

    override val name = "Mistral ($model)"
    override val isConfigured: Boolean get() = apiKey.isNotBlank()

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
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                messages.forEach { (role, content) ->
                    addJsonObject { put("role", role); put("content", content) }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(Json.parseToJsonElement(it)) }
                }
                put("tool_choice", "auto")
            }
        }

        val req = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        val resp = withContext(Dispatchers.IO) { call.execute() }

        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: "HTTP ${resp.code}"
            Log.e(TAG, "API error: $err")
            close(IOException(err)); return@callbackFlow
        }

        resp.body?.source()?.let { src ->
            withContext(Dispatchers.IO) {
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val json  = Json.parseToJsonElement(data).jsonObject
                        val delta = json["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("delta")?.jsonObject
                        delta?.get("tool_calls")?.jsonArray?.firstOrNull()?.jsonObject?.let { tc ->
                            val fn   = tc["function"]?.jsonObject
                            val name = fn?.get("name")?.jsonPrimitive?.content ?: ""
                            val args = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                            trySend("[TOOL_CALL]{\"name\":\"$name\",\"args\":$args}")
                        }
                        delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { trySend(it) }
                    } catch (_: Exception) {}
                }
            }
        }
        close()
        awaitClose { call.cancel() }
    }
}
