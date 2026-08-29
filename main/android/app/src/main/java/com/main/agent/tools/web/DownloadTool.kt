package com.main.agent.tools.web

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024 // 200MB cap against unbounded OOM/disk-fill

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
        .followRedirects(false) // SsrfGuard.executeSafely re-validates and follows redirects itself
        .build()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'url' argument")
        val filenameHint = args["filename"]?.jsonPrimitive?.content?.trim()

        SsrfGuard.validate(url)?.let { return ToolResult.Error(it) }

        return try {
            withContext(Dispatchers.IO) {
                val resp = SsrfGuard.executeSafely(client, url)
                resp.use {
                    if (!resp.isSuccessful) {
                        return@withContext ToolResult.Error("HTTP ${resp.code} for $url",
                            ToolResult.ErrorCode.NETWORK_ERROR)
                    }

                    val body = resp.body ?: return@withContext ToolResult.Error("Empty response body")

                    val filename = filenameHint ?: extractFilename(resp)
                    val folderUri = agentFolderUri
                    if (folderUri.isNullOrBlank()) {
                        return@withContext ToolResult.Error("Agent folder not set. Configure it in Settings.")
                    }

                    val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                        ?: return@withContext ToolResult.Error("Cannot access agent folder",
                            ToolResult.ErrorCode.PERMISSION_DENIED)

                    val safeName = sanitizeFilename(filename)
                    val file = folder.createFile(body.contentType()?.type ?: "application/octet-stream", safeName)
                        ?: return@withContext ToolResult.Error("Failed to create file in agent folder")

                    val written = context.contentResolver.openOutputStream(file.uri)?.use { out ->
                        streamBounded(body.byteStream(), out, MAX_DOWNLOAD_BYTES)
                    } ?: return@withContext ToolResult.Error("Failed to write file")

                    if (written < 0) {
                        file.delete()
                        return@withContext ToolResult.Error(
                            "File exceeds the ${MAX_DOWNLOAD_BYTES / (1024 * 1024)}MB download limit")
                    }

                    ToolResult.Success("Downloaded $safeName ($written bytes)")
                }
            }
        } catch (e: SsrfBlockedException) {
            ToolResult.Error(e.message ?: "Blocked by SSRF guard")
        } catch (e: Exception) {
            ToolResult.Error("Download failed: ${e.message}",
                ToolResult.ErrorCode.NETWORK_ERROR)
        }
    }

    /** Streams [ins] into [out] in chunks, aborting once [maxBytes] would be exceeded.
     *  @return bytes written, or -1 if the cap was hit (caller should discard the partial file). */
    private fun streamBounded(ins: java.io.InputStream, out: java.io.OutputStream, maxBytes: Long): Long {
        ins.use {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n == -1) break
                total += n
                if (total > maxBytes) return -1
                out.write(buf, 0, n)
            }
            return total
        }
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
}
