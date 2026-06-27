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

private const val TAG = "AnthropicProvider"
private const val BASE_URL = "https://api.anthropic.com/v1/messages"

class AnthropicProvider(
    private val apiKey: String,
    val model: String = "claude-sonnet-4-5",
) : CloudProvider {

    override val name = "Anthropic ($model)"
    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val client = OkHttpClient.Builder().build()

    private data class ToolAccum(val name: String, val args: StringBuilder = StringBuilder())

    override fun complete(
        messages: List<Pair<String, String>>,
        tools: List<String>,
        temperature: Float,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val systemMsg = messages.firstOrNull { it.first == "system" }?.second ?: ""
        val chatMsgs  = messages.filter { it.first != "system" }

        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            if (systemMsg.isNotBlank()) put("system", systemMsg)
            putJsonArray("messages") {
                chatMsgs.forEach { (role, content) ->
                    addJsonObject { put("role", role); put("content", content) }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { toolJson ->
                        val t = Json.parseToJsonElement(toolJson).jsonObject
                        addJsonObject {
                            put("name",         t["name"] ?: JsonPrimitive(""))
                            put("description",  t["description"] ?: JsonPrimitive(""))
                            put("input_schema", t["parameters"] ?: buildJsonObject {})
                        }
                    }
                }
            }
        }

        val req = Request.Builder()
            .url(BASE_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
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

        // index → accumulator for streaming tool call args
        val toolAccum = mutableMapOf<Int, ToolAccum>()

        resp.body?.source()?.let { src ->
            withContext(Dispatchers.IO) {
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    try {
                        val json      = Json.parseToJsonElement(data).jsonObject
                        val eventType = json["type"]?.jsonPrimitive?.content
                        val blockIdx  = json["index"]?.jsonPrimitive?.intOrNull ?: 0

                        when (eventType) {
                            "content_block_start" -> {
                                val block = json["content_block"]?.jsonObject
                                if (block?.get("type")?.jsonPrimitive?.content == "tool_use") {
                                    val name = block["name"]?.jsonPrimitive?.content ?: ""
                                    toolAccum[blockIdx] = ToolAccum(name)
                                }
                            }
                            "content_block_delta" -> {
                                val delta = json["delta"]?.jsonObject
                                when (delta?.get("type")?.jsonPrimitive?.content) {
                                    "text_delta" ->
                                        delta["text"]?.jsonPrimitive?.contentOrNull?.let { trySend(it) }
                                    "input_json_delta" ->
                                        delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                            ?.let { toolAccum[blockIdx]?.args?.append(it) }
                                }
                            }
                            "content_block_stop" -> {
                                toolAccum.remove(blockIdx)?.let { accum ->
                                    val argsJson = accum.args.toString().ifBlank { "{}" }
                                    trySend("[TOOL_CALL]{\"name\":\"${accum.name}\",\"args\":$argsJson}[/TOOL_CALL]")
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
