package com.main.agent.llm.cloud

import kotlinx.coroutines.flow.Flow

/** Common interface for all cloud LLM backends. */
interface CloudProvider {
    val name: String

    /**
     * Stream a chat completion.
     * @param messages List of (role, content) pairs.  role = "system"|"user"|"assistant"
     * @param tools    Optional JSON list of tool schemas in OpenAI format.
     * @return Flow of token strings.  Emits a special "[TOOL_CALL]…" string for tool calls.
     */
    fun complete(
        messages: List<Pair<String, String>>,
        tools: List<String> = emptyList(),
        temperature: Float   = 0.7f,
        maxTokens: Int       = 1024,
    ): Flow<String>

    /** True if the provider is configured and can make requests. */
    val isConfigured: Boolean
}
