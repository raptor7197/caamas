package com.main.agent.tools.base

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

/** True if [permission] is currently granted — check before device access instead of trusting an empty result. */
fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun permissionDeniedResult(permission: String): ToolResult =
    ToolResult.Error("Permission not granted: $permission", ToolResult.ErrorCode.PERMISSION_DENIED)
