package com.main.agent.tools.base

import android.content.Context
import android.util.Log
import com.main.agent.rag.RAGRetriever
import com.main.agent.tools.device.*
import com.main.agent.tools.knowledge.*
import com.main.agent.tools.system.*
import com.main.agent.tools.web.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val TAG = "ToolRegistry"

class ToolRegistry(
    agentFolderUri: String? = null,
    ragRetriever:   RAGRetriever? = null,
) {
    private val ragTool = RAGTool()

    private val tools: Map<String, Tool> = buildMap {
        put("calculator",      CalculatorTool())
        put("get_weather",     WeatherTool())
        put("open_maps",       MapsTool())
        put("web_search",      WebSearchTool())
        put("browse_url",      BrowserTool())
        put("read_rag",        ragTool)

        put("device_settings", SettingsTool())
        put("take_screenshot", ScreenshotTool())
        put("clipboard",       ClipboardTool())

        put("take_photo",       CameraTool())
        put("search_contacts",  ContactsTool())
        put("send_sms",         SmsTool())
        put("make_call",        PhoneTool())
        put("calendar",         AlarmCalendarTool())
        put("download_file",    DownloadTool(agentFolderUri))
    }

    init {
        ragTool.retriever = ragRetriever
    }

    fun allSchemas(): String =
        tools.values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.schema }

    fun schemasForQuery(query: String): String {
        val lower = query.lowercase()
        val relevant = tools.values.filter { tool ->
            when (tool.name) {
                "calculator"      -> Regex("""\d|calc|math|percent|convert|how many|how much""").containsMatchIn(lower)
                "get_weather"     -> listOf("weather", "temperature", "forecast", "rain", "snow", "hot", "cold", "humid").any { it in lower }
                "open_maps"       -> listOf("map", "direction", "navigate", "where is", "near", "route").any { it in lower }
                "web_search"      -> listOf("search", "find", "who is", "what is", "when did", "latest", "news", "look up").any { it in lower }
                "browse_url"      -> listOf("http", "url", "website", "open link", "visit", "open page").any { it in lower }
                "read_rag"        -> listOf("document", "file", "notes", "pdf", "folder", "my files").any { it in lower }
                "device_settings" -> listOf("setting", "wifi", "bluetooth", "volume", "brightness", "notification").any { it in lower }
                "take_screenshot" -> listOf("screenshot", "capture screen", "screen shot").any { it in lower }
                "clipboard"       -> listOf("clipboard", "copy", "paste", "copied").any { it in lower }
                "take_photo"      -> listOf("photo", "picture", "camera", "selfie", "take a pic").any { it in lower }
                "search_contacts" -> listOf("contact", "phone number", "email of", "number of").any { it in lower }
                "send_sms"        -> listOf("sms", "text message", "send message", "send a text").any { it in lower }
                "make_call"       -> listOf("call", "phone", "dial", "ring", "make a call").any { it in lower }
                "calendar"        -> listOf("calendar", "schedule", "remind", "alarm", "event", "appointment", "meeting").any { it in lower }
                "download_file"   -> listOf("download", "save file", "fetch url").any { it in lower }
                else              -> true
            }
        }
        val finalSet = relevant.ifEmpty {
            listOfNotNull(tools["web_search"], tools["calculator"], tools["get_weather"])
        }
        return finalSet.joinToString(prefix = "[", postfix = "]", separator = ",") { it.schema }
    }

    suspend fun execute(context: Context, toolName: String, argsJson: String): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.Error("Unknown tool: $toolName", ToolResult.ErrorCode.NOT_FOUND)

        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.Error(
                "Invalid args JSON for $toolName: ${e.message}",
                ToolResult.ErrorCode.PARSE_ERROR,
            )
        }

        return try {
            val result = tool.execute(context, args)
            Log.d(TAG, "$toolName -> ${result.toLogString()}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "$toolName threw: ${e.message}", e)
            ToolResult.Error(
                "Tool $toolName crashed: ${e.message}",
                ToolResult.ErrorCode.UNKNOWN,
            )
        }
    }
}
