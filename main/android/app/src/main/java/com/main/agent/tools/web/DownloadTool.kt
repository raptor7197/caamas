package com.main.agent.tools.web

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class DownloadTool(private val agentFolderUri: String?) : Tool {
    override val name        = "download_file"
    override val description = "Download a file from a public URL into the agent folder."
    override val schema = """{"type":"function","function":{"name":"download_file","description":"$description",
        "parameters":{"type":"object","properties":{
        "url":{"type":"string","description":"Public URL to download"},
        "filename":{"type":"string","description":"Optional filename (default: from Content-Disposition or URL)"}},
        "required":["url"]}}}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'url' argument")
        val filenameHint = args["filename"]?.jsonPrimitive?.content?.trim()

        val validation = validateUrl(url)
        if (validation != null) return validation

        return try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) {
                return ToolResult.Error("HTTP ${resp.code} for $url",
                    ToolResult.ErrorCode.NETWORK_ERROR)
            }

            val body = resp.body ?: return ToolResult.Error("Empty response body")

            val filename = filenameHint ?: extractFilename(resp)
            val folderUri = agentFolderUri
            if (folderUri.isNullOrBlank()) {
                return ToolResult.Error("Agent folder not set. Configure it in Settings.")
            }

            val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                ?: return ToolResult.Error("Cannot access agent folder",
                    ToolResult.ErrorCode.PERMISSION_DENIED)

            val safeName = sanitizeFilename(filename)
            val file = folder.createFile(body.contentType()?.type ?: "application/octet-stream", safeName)
                ?: return ToolResult.Error("Failed to create file in agent folder")

            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(body.bytes())
            } ?: return ToolResult.Error("Failed to write file")

            ToolResult.Success("Downloaded $safeName (${body.contentLength()} bytes)")
        } catch (e: Exception) {
            ToolResult.Error("Download failed: ${e.message}",
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

        if (PRIVATE_HOSTS.any { host == it } ||
            host.endsWith(".local") || host.endsWith(".internal")) {
            return ToolResult.Error("Cannot download from private/internal hosts")
        }
        if (PRIVATE_IP_PREFIXES.any { host.startsWith(it) }) {
            return ToolResult.Error("Cannot download from private IP addresses")
        }
        return null
    }

    private fun extractFilename(response: okhttp3.Response): String {
        val disposition = response.header("Content-Disposition")
        if (disposition != null) {
            val match = Regex("filename=\"?([^\";\n]+)\"?").find(disposition)
            if (match != null) return match.groupValues[1]
        }
        val path = response.request.url.encodedPath
        val name = path.substringAfterLast('/')
        if (name.isNotBlank()) return name
        return "download_${System.currentTimeMillis()}"
    }

    private fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (sanitized.length > 120) return sanitized.take(120)
        if (sanitized.isBlank()) return "download_${System.currentTimeMillis()}"
        return sanitized
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
