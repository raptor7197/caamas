package com.main.agent.tools.web

import android.content.Context
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WebSearchTool : Tool {
    override val name        = "web_search"
    override val description = "Search the web via DuckDuckGo. Returns up to 5 results with title and snippet."
    override val schema = """{"type":"function","function":{"name":"web_search","description":"$description",
        "parameters":{"type":"object","properties":{
        "query":{"type":"string","description":"Search query"}},
        "required":["query"]}}}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'query' argument")

        return try {
            val result = withContext(Dispatchers.IO) { tryDuckDuckGo(query, 5) }
            if (result != null) {
                ToolResult.Success(result)
            } else {
                ToolResult.Error("No search results found", ToolResult.ErrorCode.NO_RESULTS)
            }
        } catch (e: Exception) {
            ToolResult.Error("Web search failed: ${e.message}",
                ToolResult.ErrorCode.NETWORK_ERROR)
        }
    }

    private fun tryDuckDuckGo(query: String, max: Int): String? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val resp = client.newCall(
            Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .build()
        ).execute()
        if (!resp.isSuccessful) return null

        val doc     = Jsoup.parse(resp.body!!.string())
        val results = doc.select(".result__body").take(max)
        if (results.isEmpty()) return null

        val sb = StringBuilder("Search results for \"$query\":\n\n")
        results.forEachIndexed { i, el ->
            val title   = el.selectFirst(".result__a")?.text()?.trim() ?: "No title"
            val url     = el.selectFirst(".result__url")?.text()?.trim() ?: ""
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim() ?: ""
            sb.appendLine("${i + 1}. $title")
            if (url.isNotBlank()) sb.appendLine("   URL: $url")
            if (snippet.isNotBlank()) sb.appendLine("   $snippet")
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }
}
