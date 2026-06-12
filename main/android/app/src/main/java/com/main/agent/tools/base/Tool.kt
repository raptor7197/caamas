package com.main.agent.tools.base

import android.content.Context
import kotlinx.serialization.json.JsonObject

/**
 * Base interface every tool must implement.
 *
 * Each tool exposes:
 *  - A JSON schema (OpenAI function-calling format) describing its parameters.
 *  - An [execute] method that returns a [ToolResult].
 */
interface Tool {
    val name:        String
    val description: String

    /** JSON schema for this tool's parameters (in OpenAI function-calling format). */
    val schema: String

    /**
     * Execute the tool with [args] parsed from the LLM's tool-call JSON.
     * Must NOT throw — all errors should be returned as [ToolResult.Error].
     */
    suspend fun execute(context: Context, args: JsonObject): ToolResult
}
