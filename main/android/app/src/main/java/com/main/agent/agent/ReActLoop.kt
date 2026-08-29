package com.main.agent.agent

import android.content.Context
import android.util.Log
import com.main.agent.tools.base.ToolRegistry
import com.main.agent.tools.base.ToolResult
import com.main.agent.llm.LlamaEngine
import com.main.agent.llm.cloud.CloudProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*

private const val TAG = "ReActLoop"
private const val TOOL_START = "[TOOL_CALL]"
private const val TOOL_END   = "[/TOOL_CALL]"
internal const val MAX_TOOL_RESULT_CHARS = 4000
internal val TOOL_CALL_RE = Regex("""\[TOOL_CALL\](.*?)\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL)

// Sentinel tokens for tool lifecycle events (STX/ETX control chars — never appear in model output)
internal const val TOOL_EVT_PFX = ""
internal const val TOOL_EVT_SFX = ""

internal fun capToolResult(content: String): String =
    if (content.length > MAX_TOOL_RESULT_CHARS) {
        content.take(MAX_TOOL_RESULT_CHARS) + "\n...[truncated]"
    } else {
        content
    }

// Strip chars that could be mistaken for sentinel tokens or trigger re-injection
private fun sanitizeToolContent(raw: String): String = raw
    .replace(TOOL_EVT_PFX, "")
    .replace(TOOL_EVT_SFX, "")
    .replace(Regex("""\[TOOL_CALL\].*?\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL), "[suppressed]")

class ReActLoop(private val engine: LlamaEngine) {

    fun run(
        context:  Context,
        messages: List<Pair<String, String>>,
        route:    Route,
        registry: ToolRegistry,
        confirmBroker: ConfirmationBroker,
        maxIter:  Int = 8,
    ): Flow<String> = flow {
        val mutableMessages = messages.toMutableList()

        for (iter in 0 until maxIter) {
            Log.d(TAG, "Iteration $iter  messages=${mutableMessages.size}")

            val responseSb    = StringBuilder()
            val displayBuffer = StringBuilder()
            var inToolCall    = false

            // Same token stream for local and cloud routes from here — both must go through
            // the same buffering so a [TOOL_CALL]...[/TOOL_CALL] split across stream chunks
            // (routine for cloud SSE deltas) never leaks into the display or gets truncated.
            val tokenFlow = when (route) {
                is Route.LocalSmall, is Route.LocalLarge -> {
                    val prompt = engine.applyChatTemplate(mutableMessages, addAssistant = true)
                    val maxTok = if (route is Route.LocalLarge) 2048 else 1536
                    engine.inference(prompt, maxTokens = maxTok)
                }
                is Route.Cloud -> route.provider.complete(mutableMessages, maxTokens = 2048)
            }

            tokenFlow.collect { token ->
                responseSb.append(token)
                displayBuffer.append(token)
                val pending = displayBuffer.toString()

                when {
                    inToolCall -> {
                        if (pending.contains(TOOL_END)) {
                            inToolCall = false
                            val after = pending.substringAfter(TOOL_END)
                            displayBuffer.clear()
                            displayBuffer.append(after)
                        }
                        // inside tool call — don't emit
                    }
                    pending.contains(TOOL_START) -> {
                        val before = pending.substringBefore(TOOL_START)
                        if (before.isNotEmpty()) emit(before)
                        inToolCall = true
                        displayBuffer.clear()
                        displayBuffer.append(TOOL_START)
                        displayBuffer.append(pending.substringAfter(TOOL_START))
                    }
                    else -> {
                        // hold back enough chars to catch a split tag at stream boundary
                        val holdLen = TOOL_START.length - 1
                        if (pending.length > holdLen) {
                            val safe = pending.dropLast(holdLen)
                            emit(safe)
                            displayBuffer.delete(0, safe.length)
                        }
                    }
                }
            }

            // flush anything left that isn't inside a tool call
            val remaining = displayBuffer.toString()
            if (remaining.isNotEmpty() && !inToolCall) emit(remaining)

            val response = responseSb.toString()
            mutableMessages.add("assistant" to response)

            val toolMatches = TOOL_CALL_RE.findAll(response).toList()
            if (toolMatches.isEmpty()) {
                Log.d(TAG, "Final answer at iter $iter")
                break
            }

            for (match in toolMatches) {
                val callJson = match.groupValues[1].trim()
                Log.d(TAG, "Tool call: ${callJson.take(120)}")

                val (toolName, toolArgs) = try {
                    val obj  = Json.parseToJsonElement(callJson).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    // Never trust a model-supplied "confirmed" — only the host may set it,
                    // after ConfirmationBroker.request() actually granted it below.
                    val argsObj = obj["args"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    argsObj.remove("confirmed")
                    val args = JsonObject(argsObj).toString()
                    name to args
                } catch (e: Exception) {
                    emit("\n⚠ Malformed tool call — asking model to retry...\n")
                    val safeErr = e.message?.replace(Regex("[\\u0000-\\u001F]"), "")?.take(200) ?: "unknown"
                    mutableMessages.add("user" to "Your tool call JSON was malformed: $safeErr. Output a valid [TOOL_CALL]{\"name\":\"...\",\"args\":{...}}[/TOOL_CALL].")
                    continue
                }

                emit("${TOOL_EVT_PFX}TOOL:$toolName${TOOL_EVT_SFX}")
                val toolResult = registry.execute(context, toolName, toolArgs)
                Log.d(TAG, "Tool result: ${toolResult.toLogString()}")

                val resultContent = when (toolResult) {
                    is ToolResult.Success -> {
                        emit("${TOOL_EVT_PFX}DONE:$toolName${TOOL_EVT_SFX}")
                        "Tool result:\n${capToolResult(sanitizeToolContent(toolResult.content))}"
                    }
                    is ToolResult.Error -> {
                        emit("${TOOL_EVT_PFX}ERR:$toolName${TOOL_EVT_SFX}")
                        "Tool error [${toolResult.errorCode}]: ${sanitizeToolContent(toolResult.message)}"
                    }
                    is ToolResult.NeedsConfirmation -> {
                        emit("\n⚠ ${toolResult.title}: ${toolResult.message}\n")
                        val confirmed = confirmBroker.request()
                        if (confirmed) {
                            val confirmedArgs = try {
                                val argsObj = Json.parseToJsonElement(toolArgs).jsonObject.toMutableMap()
                                argsObj["confirmed"] = JsonPrimitive(true)
                                JsonObject(argsObj).toString()
                            } catch (_: Exception) { toolResult.jsonData.ifBlank { "{\"confirmed\":true}" } }
                            val retryResult = registry.execute(context, toolName, confirmedArgs)
                            when (retryResult) {
                                is ToolResult.Success -> {
                                    emit("${TOOL_EVT_PFX}DONE:$toolName${TOOL_EVT_SFX}")
                                    "Tool result:\n${capToolResult(sanitizeToolContent(retryResult.content))}"
                                }
                                is ToolResult.Error -> {
                                    emit("${TOOL_EVT_PFX}ERR:$toolName${TOOL_EVT_SFX}")
                                    "Tool error [${retryResult.errorCode}]: ${sanitizeToolContent(retryResult.message)}"
                                }
                                else -> {
                                    emit("${TOOL_EVT_PFX}ERR:$toolName${TOOL_EVT_SFX}")
                                    "Tool result unavailable"
                                }
                            }
                        } else {
                            emit("${TOOL_EVT_PFX}ERR:$toolName${TOOL_EVT_SFX}")
                            "User denied confirmation for $toolName — skipping."
                        }
                    }
                }

                // Use "user" role — "tool" role unsupported by all cloud APIs
                // in text-based ReAct mode; local chat templates map it correctly too.
                mutableMessages.add("user" to resultContent)
            }

            if (iter == maxIter - 1) {
                emit("\n\nReached max iterations ($maxIter). Last result: ${mutableMessages.last().second.take(80)}")
            }
        }
    }
}
