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
private val TOOL_CALL_RE = Regex("""\[TOOL_CALL\](.*?)\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL)

class ReActLoop(private val engine: LlamaEngine) {

    fun run(
        context:      Context,
        messages:     List<Pair<String, String>>,
        route:        Route,
        registry:     ToolRegistry,
        maxIter:      Int = 8,
    ): Flow<String> = flow {
        val mutableMessages = messages.toMutableList()

        for (iter in 0 until maxIter) {
            Log.d(TAG, "Iteration $iter  messages=${mutableMessages.size}")

            val responseSb = StringBuilder()

            when (route) {
                is Route.LocalSmall, is Route.LocalLarge -> {
                    val prompt = engine.applyChatTemplate(mutableMessages, addAssistant = true)
                    val maxTok = if (route is Route.LocalLarge) 1024 else 512
                    engine.inference(prompt, maxTokens = maxTok).collect { token ->
                        responseSb.append(token)
                        if (!token.contains("[TOOL_CALL]")) emit(token)
                    }
                }
                is Route.Cloud -> {
                    route.provider.complete(mutableMessages).collect { token ->
                        responseSb.append(token)
                        if (!token.startsWith("[TOOL_CALL]")) emit(token)
                    }
                }
            }

            val response = responseSb.toString()
            mutableMessages.add("assistant" to response)

            val toolMatch = TOOL_CALL_RE.find(response)
            if (toolMatch == null) {
                Log.d(TAG, "Final answer at iter $iter")
                break
            }

            val callJson = toolMatch.groupValues[1].trim()
            Log.d(TAG, "Tool call: ${callJson.take(120)}")

            val (toolName, toolArgs) = try {
                val obj  = Json.parseToJsonElement(callJson).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                val args = obj["args"]?.jsonObject?.toString() ?: "{}"
                name to args
            } catch (e: Exception) {
                emit("\n\u26A0 Could not parse tool call: ${e.message}\n")
                break
            }

            emit("\n\u2699 Running tool: $toolName\u2026\n")
            val toolResult = registry.execute(context, toolName, toolArgs)
            Log.d(TAG, "Tool result: ${toolResult.toLogString()}")

            val resultContent = when (toolResult) {
                is ToolResult.Success -> {
                    val sanitized = toolResult.content.replace(Regex("""\[TOOL_CALL\].*?\[/TOOL_CALL\]""", RegexOption.DOT_MATCHES_ALL), "[tool result suppressed]")
                    "Tool result:\n$sanitized"
                }
                is ToolResult.Error -> "Tool error [${toolResult.errorCode}]: ${toolResult.message}"
                is ToolResult.NeedsConfirmation -> {
                    emit("\n\u26A0 Confirmation required: ${toolResult.prompt}\n")
                    "Tool requires user confirmation. Asking user\u2026"
                }
            }

            mutableMessages.add("tool" to resultContent)

            if (iter == maxIter - 1) {
                emit("\n\nI tried $maxIter times but couldn't complete the task. Last result: ${toolResult.toLogString()}")
            }
        }
    }
}
