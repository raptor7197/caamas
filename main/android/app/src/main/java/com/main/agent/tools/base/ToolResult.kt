package com.main.agent.tools.base

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/** Sealed result returned by every tool. */
sealed class ToolResult {

    /** The tool succeeded.  [content] is the text the LLM should see. */
    data class Success(val content: String) : ToolResult()

    /**
     * The tool failed with a recoverable or informational error.
     * [message] will be shown to the LLM so it can try an alternative approach.
     */
    data class Error(
        val message:   String,
        val errorCode: ErrorCode = ErrorCode.UNKNOWN,
    ) : ToolResult()

    /** A dangerous action (SMS send, call, screenshot) needs explicit user confirmation. */
    data class NeedsConfirmation(
        val prompt:    String,        // confirmation ID for the UI to match
        val title:     String,        // human-readable title
        val message:   String,        // detailed description
        val jsonData:  String = "",   // JSON payload for re-trying the action
    ) : ToolResult()

    enum class ErrorCode {
        UNKNOWN,
        PERMISSION_DENIED,
        NETWORK_ERROR,
        TIMEOUT,
        PARSE_ERROR,
        NOT_FOUND,
        NO_RESULTS,
        RATE_LIMITED,
        DEVICE_UNSUPPORTED,
    }

    /** Convenience: is this a success? */
    val isSuccess: Boolean get() = this is Success

    /** Convenience: brief string for logging (avoids leaking PII to logcat). */
    fun toLogString(): String = when (this) {
        is Success           -> "OK: ${content.take(40)}"
        is Error             -> "ERR[$errorCode]: ${message.take(80)}"
        is NeedsConfirmation -> "CONFIRM: $prompt"
    }
}

fun buildJsonString(block: JsonObjectBuilder.() -> Unit): String {
    return Json.encodeToString(JsonObject.serializer(), buildJsonObject(block))
}
