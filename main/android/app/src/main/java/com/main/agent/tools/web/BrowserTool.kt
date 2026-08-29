package com.main.agent.tools.web

import android.content.Context
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2MB of HTML is plenty for text extraction

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
        .followRedirects(false) // SsrfGuard.executeSafely re-validates and follows redirects itself
        .followSslRedirects(false)
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

        SsrfGuard.validate(url)?.let { return ToolResult.Error(it) }

        return try {
            withContext(Dispatchers.IO) {
                val resp = SsrfGuard.executeSafely(client, url)
                resp.use {
                    if (!resp.isSuccessful) {
                        return@withContext ToolResult.Error("HTTP ${resp.code} for $url",
                            ToolResult.ErrorCode.NETWORK_ERROR)
                    }

                    val contentType = resp.header("Content-Type", "") ?: ""
                    if (!contentType.contains("text/html", ignoreCase = true) &&
                        !contentType.contains("text/plain", ignoreCase = true)) {
                        return@withContext ToolResult.Error("Unsupported content type: $contentType")
                    }

                    val html = resp.body?.let { readBounded(it, MAX_BODY_BYTES) } ?: ""
                    val doc: Document = Jsoup.parse(html, url)
                    doc.select("script, style, nav, footer, header, iframe, noscript").remove()

                    val title   = doc.title().take(200)
                    val body    = doc.body()?.text()?.trim() ?: ""
                    val content = if (body.length <= maxChars) body else body.take(maxChars) + "\n[truncated]"

                    ToolResult.Success("Title: $title\n\n$content")
                }
            }
        } catch (e: SsrfBlockedException) {
            ToolResult.Error(e.message ?: "Blocked by SSRF guard")
        } catch (e: IllegalArgumentException) {
            ToolResult.Error("Invalid URL: ${e.message}")
        } catch (e: UnknownHostException) {
            ToolResult.Error("Host not found: ${e.message}", ToolResult.ErrorCode.NETWORK_ERROR)
        } catch (e: Exception) {
            ToolResult.Error("Failed to fetch $url: ${e.message}",
                ToolResult.ErrorCode.NETWORK_ERROR)
        }
    }

    private fun readBounded(body: ResponseBody, maxBytes: Int): String {
        body.byteStream().use { ins ->
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (total < maxBytes) {
                val n = ins.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n == -1) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toString("UTF-8")
        }
    }
}
