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
private val TOOL_CALL_RE = Regex("""\[TOOL_CALL\](.*?)\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL)

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

            when (route) {
                is Route.LocalSmall, is Route.LocalLarge -> {
                    val prompt  = engine.applyChatTemplate(mutableMessages, addAssistant = true)
                    val maxTok  = if (route is Route.LocalLarge) 1024 else 512

                    engine.inference(prompt, maxTokens = maxTok).collect { token ->
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
                }

                is Route.Cloud -> {
                    route.provider.complete(mutableMessages).collect { token ->
                        responseSb.append(token)
                        if (!token.startsWith(TOOL_START)) emit(token)
                    }
                }
            }

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
                    val args = obj["args"]?.jsonObject?.toString() ?: "{}"
                    name to args
                } catch (e: Exception) {
                    emit("\n⚠ Could not parse tool call: ${e.message}\n")
                    continue
                }

                emit("\n⚙ Running tool: $toolName…\n")
                val toolResult = registry.execute(context, toolName, toolArgs)
                Log.d(TAG, "Tool result: ${toolResult.toLogString()}")

                val resultContent = when (toolResult) {
                    is ToolResult.Success -> {
                        val sanitized = toolResult.content.replace(
                            Regex("""\[TOOL_CALL\].*?\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL),
                            "[tool result suppressed]"
                        )
                        "Tool result:\n$sanitized"
                    }
                    is ToolResult.Error -> "Tool error [${toolResult.errorCode}]: ${toolResult.message}"
                    is ToolResult.NeedsConfirmation -> {
                        emit("\n⚠ ${toolResult.title}: ${toolResult.message}\n")
                        val confirmed = confirmBroker.request()  // suspends until UI responds
                        if (confirmed) {
                            // Retry with confirmed flag in args
                            val confirmedArgs = toolResult.jsonData.ifBlank { "{\"confirmed\":true}" }
                            val retryResult = registry.execute(context, toolName, confirmedArgs)
                            when (retryResult) {
                                is ToolResult.Success -> "Tool result:\n${retryResult.content}"
                                is ToolResult.Error   -> "Tool error [${retryResult.errorCode}]: ${retryResult.message}"
                                else                  -> "Tool result unavailable"
                            }
                        } else {
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
