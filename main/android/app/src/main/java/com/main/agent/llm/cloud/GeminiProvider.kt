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

private const val TAG = "GeminiProvider"
private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

class GeminiProvider(
    private val apiKey: String,
    val model: String = "gemini-2.0-flash",
) : CloudProvider {

    override val name = "Gemini ($model)"
    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val client = OkHttpClient.Builder().build()

    override fun complete(
        messages: List<Pair<String, String>>,
        tools: List<String>,
        temperature: Float,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val systemMsg = messages.firstOrNull { it.first == "system" }?.second ?: ""
        val chatMsgs  = messages.filter { it.first != "system" }

        val body = buildJsonObject {
            putJsonArray("contents") {
                chatMsgs.forEach { (role, content) ->
                    addJsonObject {
                        put("role", when (role) {
                            "assistant" -> "model"
                            "tool" -> "user"
                            else -> role
                        })
                        putJsonArray("parts") {
                            addJsonObject { put("text", content) }
                        }
                    }
                }
            }
            if (systemMsg.isNotBlank()) {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemMsg) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature.toDouble())
                put("maxOutputTokens", maxTokens)
            }
        }

        val url = "$BASE_URL/$model:streamGenerateContent?alt=sse"
        Log.d(TAG, "POST $url")

        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
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
                        val json = Json.parseToJsonElement(data).jsonObject
                        val text = json["candidates"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonObject
                            ?.get("parts")?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("text")?.jsonPrimitive?.contentOrNull
                        if (text != null) {
                            trySend(text)
                        } else {
                            json["promptFeedback"]?.jsonObject?.let { pf ->
                                val reason = pf["blockReason"]?.jsonPrimitive?.contentOrNull
                                if (reason != null) {
                                    val err = "Blocked: $reason"
                                    Log.e(TAG, err)
                                    close(IOException(err))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SSE parse error: ${e.message}")
                    }
                }
            }
        }
        close()
        awaitClose { call.cancel() }
    }
}
