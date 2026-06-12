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
        put("search_web",      WebSearchTool())
        put("fetch_url",       BrowserTool())
        put("read_rag",        ragTool)

        put("toggle_settings", SettingsTool())
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
