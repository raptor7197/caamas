package com.main.agent.tools.web

import android.content.Context
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class BrowserTool : Tool {
    override val name        = "browse_url"
    override val description = "Fetch and extract readable content from a URL."
    override val schema = """{"type":"function","function":{"name":"browse_url","description":"$description",
        "parameters":{"type":"object","properties":{
        "url":{"type":"string","description":"Full URL (http/https only, public hosts)"},
        "max_chars":{"type":"integer","description":"Max characters to return","default":4000}},
        "required":["url"]}}}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Agent/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'url' argument")
        val maxChars = (args["max_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4000)
            .coerceIn(256, 16000)

        val validation = validateUrl(url)
        if (validation != null) return validation

        return try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) {
                return ToolResult.Error("HTTP ${resp.code} for $url",
                    ToolResult.ErrorCode.NETWORK_ERROR)
            }

            val contentType = resp.header("Content-Type", "") ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) &&
                !contentType.contains("text/plain", ignoreCase = true)) {
                return ToolResult.Error("Unsupported content type: $contentType")
            }

            val doc: Document = Jsoup.parse(resp.body!!.string(), url)
            doc.select("script, style, nav, footer, header, iframe, noscript").remove()

            val title   = doc.title().take(200)
            val body    = doc.body()?.text()?.trim() ?: ""
            val content = if (body.length <= maxChars) body else body.take(maxChars) + "\n[truncated]"

            ToolResult.Success("Title: $title\n\n$content")
        } catch (e: IllegalArgumentException) {
            ToolResult.Error("Invalid URL: ${e.message}")
        } catch (e: UnknownHostException) {
            ToolResult.Error("Host not found: ${e.message}", ToolResult.ErrorCode.NETWORK_ERROR)
        } catch (e: Exception) {
            ToolResult.Error("Failed to fetch $url: ${e.message}",
                ToolResult.ErrorCode.NETWORK_ERROR)
        }
    }

    private fun validateUrl(rawUrl: String): ToolResult? {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            return ToolResult.Error("URL must start with http:// or https://")
        }
        val uri = try { URI(rawUrl) } catch (e: Exception) {
            return ToolResult.Error("Invalid URL format")
        }
        val host = uri.host?.lowercase() ?: return ToolResult.Error("URL has no host")
        if (host.contains(" ")) return ToolResult.Error("Invalid host")

        if (PRIVATE_HOSTS.any { host == it } ||
            host.endsWith(".local") ||
            host.endsWith(".internal")) {
            return ToolResult.Error("Cannot browse private/internal hosts")
        }

        if (PRIVATE_IP_PREFIXES.any { host.startsWith(it) }) {
            return ToolResult.Error("Cannot browse private IP addresses")
        }

        return null
    }

    companion object {
        private val PRIVATE_HOSTS = setOf(
            "localhost", "127.0.0.1", "127.0.1.1", "0.0.0.0",
            "metadata.google.internal", "169.254.169.254",
            "::1", "[::1]",
        )
        private val PRIVATE_IP_PREFIXES = listOf(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.",
            "172.28.", "172.29.", "172.30.", "172.31.",
            "192.168.", "169.254.",
        )
    }
}
