package com.main.agent.agent

import android.content.Context
import android.util.Log
import com.main.agent.llm.DeviceCapability
import com.main.agent.llm.LlamaEngine
import com.main.agent.llm.cloud.CloudProvider
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.tools.base.ToolRegistry
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val TAG        = "AgentCore"
private const val MAX_ITER   = 8          // maximum ReAct iterations
private const val SYSTEM_PROMPT = """You are a helpful, private AI assistant running fully on-device.
You have access to tools. When you need to use a tool, output EXACTLY:
[TOOL_CALL]{"name":"<tool_name>","args":{...}}[/TOOL_CALL]
Then wait for the result before continuing.
Always give a final direct answer to the user.
Be concise. Respect privacy. Do not hallucinate tool results."""

/**
 * Central orchestration layer.
 *
 * Handles:
 * - Prompt assembly (system prompt + history + RAG context + tool schemas)
 * - Routing to local small / large model or cloud
 * - ReAct tool-call loop
 * - Session management
 */
class AgentCore(
    private val context:      Context,
    private val engine:       LlamaEngine,
    val capability:           DeviceCapability.Info,
    private val toolRegistry: ToolRegistry,
    private val router:       AgentRouter,
    private val reactLoop:    ReActLoop,
) {

    /**
     * Process a user message and stream the agent's response.
     *
     * @param userMessage  Raw text from the user (already transcribed from voice if needed).
     * @param history      Prior (role, content) pairs for this session.
     * @return Flow of strings — partial tokens for streaming display.
     *         Tool-use steps are also streamed as status lines prefixed with "⚙ ".
     */
    fun respond(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
    ): Flow<String> = flow {
        Log.d(TAG, "Processing: ${userMessage.take(80)}")

        // 1. Build conversation with tools schema injected into system prompt
        val toolSchemas  = toolRegistry.allSchemas()
        val fullSystem   = "$SYSTEM_PROMPT\n\nAvailable tools:\n$toolSchemas"
        val messages     = buildList {
            add("system"    to fullSystem)
            addAll(history.takeLast(20))   // keep last 20 turns for context
            add("user"      to userMessage)
        }

        // 2. Route: decide which model backbone to use
        val route = router.route(userMessage, history.size, capability)
        Log.d(TAG, "Route: $route")

        // 3. Run ReAct loop — emits streaming tokens
        emitAll(reactLoop.run(context, messages, route, toolRegistry, MAX_ITER))
    }
}
