package com.main.agent.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    // sourcePath -> lastModified at the time it was last indexed. Presence of this file is
    // also how indexIfNeeded() tells "first run" (needs indexAll) from "already indexed"
    // (deltaUpdate suffices) apart.
    private val metaFile = File(context.filesDir, "rag/index_meta.tsv").also { it.parentFile?.mkdirs() }

    /** Full index on first run; cheap changed/new/removed-file-only update afterwards. */
    suspend fun indexIfNeeded(): Int = if (metaFile.exists()) deltaUpdate() else indexAll()

    suspend fun indexAll(): Int = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(agentFolderUri)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext 0

        vectorStore.clear()
        val files = collectSupportedFiles(rootDir)
        val meta = mutableMapOf<String, Long>()
        var totalChunks = 0

        for (file in files) {
            val text = readFileContent(file) ?: continue
            if (text.isBlank()) continue

            val sourcePath = file.uri.toString()
            for ((chunkText, _) in chunkText(text, sourcePath)) {
                val embedding = embedEngine.embed(chunkText) ?: continue
                vectorStore.insert(sourcePath, chunkText, embedding)
                totalChunks++
            }
            meta[sourcePath] = file.lastModified()
        }

        vectorStore.save()
        saveMeta(meta)
        Log.i(TAG, "Indexed $totalChunks chunks from agent folder")
        totalChunks
    }

    suspend fun deltaUpdate(): Int = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(agentFolderUri)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext 0

        val meta = loadMeta()
        val files = collectSupportedFiles(rootDir)
        val seen = mutableSetOf<String>()
        var newChunks = 0
        var changedFiles = 0

        for (file in files) {
            val sourcePath = file.uri.toString()
            seen.add(sourcePath)
            val mtime = file.lastModified()
            if (meta[sourcePath] == mtime) continue // unchanged since last index

            vectorStore.deleteBySource(sourcePath)
            val text = readFileContent(file)
            if (text.isNullOrBlank()) {
                meta.remove(sourcePath)
                continue
            }
            for ((chunkText, _) in chunkText(text, sourcePath)) {
                val embedding = embedEngine.embed(chunkText) ?: continue
                vectorStore.insert(sourcePath, chunkText, embedding)
                newChunks++
            }
            meta[sourcePath] = mtime
            changedFiles++
        }

        val removedPaths = meta.keys - seen
        for (path in removedPaths) {
            vectorStore.deleteBySource(path)
            meta.remove(path)
        }

        if (newChunks > 0 || removedPaths.isNotEmpty()) vectorStore.save()
        saveMeta(meta)
        Log.i(TAG, "Delta update: $newChunks new chunks, $changedFiles changed files, ${removedPaths.size} removed")
        newChunks
    }

    private fun collectSupportedFiles(rootDir: DocumentFile): List<DocumentFile> {
        val processedDirs = mutableSetOf<String>()
        val found = mutableListOf<DocumentFile>()

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
                    if (ext in SUPPORTED_EXTENSIONS) found.add(file)
                }
            }
        }
        walkDir(rootDir)

        if (found.size <= config.maxIndexedFiles) return found
        Log.w(TAG, "Agent folder has ${found.size} indexable files — capping at ${config.maxIndexedFiles}")
        return found.take(config.maxIndexedFiles)
    }

    private fun loadMeta(): MutableMap<String, Long> {
        if (!metaFile.exists()) return mutableMapOf()
        return try {
            metaFile.readLines().mapNotNull { line ->
                val sep = line.lastIndexOf('\t')
                val mtime = if (sep < 0) null else line.substring(sep + 1).toLongOrNull()
                if (sep < 0 || mtime == null) null else line.substring(0, sep) to mtime
            }.toMap(mutableMapOf())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read index metadata: ${e.message}")
            mutableMapOf()
        }
    }

    private fun saveMeta(meta: Map<String, Long>) {
        try {
            metaFile.writeText(meta.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write index metadata: ${e.message}")
        }
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
                val keepLen = overlap.coerceAtMost(current.length)
                val tail = current.toString().takeLast(keepLen) // capture before clear() empties current
                currentStart += current.length - keepLen
                current.clear()
                current.append(tail)
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
