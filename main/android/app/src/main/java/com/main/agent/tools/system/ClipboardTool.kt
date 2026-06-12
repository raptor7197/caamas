package com.main.agent.tools.system

import android.content.Context
import android.content.ClipboardManager
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClipboardTool : Tool {
    override val name        = "clipboard"
    override val description = "Read or write the system clipboard. Reading returns only the first 30 characters."
    override val schema = """{"type":"function","function":{"name":"clipboard","description":"$description",
        "parameters":{"type":"object","properties":{
        "action":{"type":"string","enum":["read","write"],"description":"'read' or 'write'"},
        "text":{"type":"string","description":"Text to write (required for 'write')"}},
        "required":["action"]}}}"""

    @Suppress("DEPRECATION")
    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val action = args["action"]?.jsonPrimitive?.content?.trim() ?: ""

        return when (action) {
            "read" -> {
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) ToolResult.Success("Clipboard is empty")
                else ToolResult.Success("Clipboard contents (truncated): ${text.take(30)}${if (text.length > 30) "\u2026" else ""}")
            }
            "write" -> {
                val text = args["text"]?.jsonPrimitive?.content?.trim()
                    ?: return ToolResult.Error("Missing 'text' for write")
                val clip = android.content.ClipData.newPlainText("agent", text)
                cm.setPrimaryClip(clip)
                ToolResult.Success("Clipboard written (${text.length} chars)")
            }
            else -> ToolResult.Error("Unknown action '$action'. Use 'read' or 'write'.")
        }
    }
}
