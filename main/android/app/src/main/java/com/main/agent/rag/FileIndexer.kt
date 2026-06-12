package com.main.agent.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FileIndexer"

private val SUPPORTED_EXTENSIONS = setOf(
    "txt", "md", "json", "csv", "xml", "html", "htm", "log",
    "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env",
    "sh", "py", "js", "ts", "kt", "java", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
)

private const val MAX_FILE_SIZE = 5 * 1024 * 1024

class FileIndexer(
    private val context: Context,
    private val agentFolderUri: String,
    private val vectorStore: VectorStore,
    private val embedEngine: EmbeddingEngine,
    private val config: VectorDbConfig,
) {
    suspend fun indexAll(): Int = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(agentFolderUri)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext 0

        vectorStore.clear()
        var totalChunks = 0
        val processedDirs = mutableSetOf<String>()

        fun walkDir(dir: DocumentFile) {
            val dirUri = dir.uri.toString()
            if (dirUri in processedDirs) return
            processedDirs.add(dirUri)

            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    walkDir(file)
                } else if (file.isFile) {
                    val name = file.name?.lowercase() ?: continue
                    val ext = name.substringAfterLast('.', "")
                    if (ext !in SUPPORTED_EXTENSIONS) continue

                    val text = readFileContent(file) ?: continue
                    if (text.isBlank()) continue

                    val sourcePath = file.uri.toString()
                    val chunks = chunkText(text, sourcePath)

                    for ((chunkText, chunkOffset) in chunks) {
                        val embedding = embedEngine.embed(chunkText) ?: continue
                        vectorStore.insert(sourcePath, chunkText, embedding)
                        totalChunks++
                    }
                }
            }
        }

        walkDir(rootDir)
        vectorStore.save()
        Log.i(TAG, "Indexed $totalChunks chunks from agent folder")
        totalChunks
    }

    suspend fun deltaUpdate(): Int = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(agentFolderUri)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext 0

        val processedDirs = mutableSetOf<String>()
        val seenUris = mutableSetOf<String>()

        fun walkDir(dir: DocumentFile) {
            val dirUri = dir.uri.toString()
            if (dirUri in processedDirs) return
            processedDirs.add(dirUri)

            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    walkDir(file)
                } else if (file.isFile) {
                    val name = file.name?.lowercase() ?: continue
                    val ext = name.substringAfterLast('.', "")
                    if (ext !in SUPPORTED_EXTENSIONS) continue
                    seenUris.add(file.uri.toString())
                }
            }
        }

        walkDir(rootDir)

        var newChunks = 0

        for (uriStr in seenUris) {
            val uri = Uri.parse(uriStr)
            val file = DocumentFile.fromSingleUri(context, uri) ?: continue

            val text = readFileContent(file) ?: continue
            if (text.isBlank()) continue

            val chunks = chunkText(text, uriStr)
            for ((chunkText, _) in chunks) {
                val embedding = embedEngine.embed(chunkText) ?: continue
                vectorStore.insert(uriStr, chunkText, embedding)
                newChunks++
            }
        }

        if (newChunks > 0) vectorStore.save()
        Log.i(TAG, "Delta update: $newChunks new chunks")
        newChunks
    }

    private fun readFileContent(file: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val bytes = stream.readNBytes(MAX_FILE_SIZE)
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ${file.uri}: ${e.message}")
            null
        }
    }

    private fun chunkText(text: String, sourcePath: String): List<Pair<String, Int>> {
        val chunks = mutableListOf<Pair<String, Int>>()
        val chunkSize = config.chunkSize
        val overlap = (chunkSize * config.chunkOverlapPct / 100).coerceAtMost(chunkSize / 2)

        val paragraphs = text.split(Regex("\n\n+"))
        val current = StringBuilder()
        var currentStart = 0

        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isBlank()) continue

            if (current.length + trimmed.length + 2 > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString() to currentStart)
                val keepLen = (overlap).coerceAtMost(current.length)
                currentStart += current.length - keepLen
                current.clear()
                if (keepLen > 0) {
                    current.append(current.toString().takeLast(keepLen))
                }
            }

            if (trimmed.length > chunkSize) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString() to currentStart)
                    current.clear()
                }
                var pos = 0
                while (pos < trimmed.length) {
                    val end = (pos + chunkSize).coerceAtMost(trimmed.length)
                    val seg = trimmed.substring(pos, end)
                    chunks.add(seg to currentStart + pos)
                    pos += chunkSize - overlap
                }
                currentStart += trimmed.length
                continue
            }

            if (current.isNotEmpty()) current.append("\n\n")
            current.append(trimmed)
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString() to currentStart)
        }

        return chunks
    }
}
