package com.main.agent.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

private const val TAG = "VectorStore"
private const val MAGIC = 0x52414756
private const val VERSION = 1

data class Chunk(
    val id: String,
    val sourcePath: String,
    val text: String,
    val embedding: FloatArray,
)

class VectorStore(context: Context, private val config: VectorDbConfig) {

    private val file: File = File(context.filesDir, "rag/vectors.bin").also {
        it.parentFile?.mkdirs()
    }

    private val chunks = mutableListOf<Chunk>()
    private val idIndex = mutableMapOf<String, Int>()

    val size: Int get() = chunks.size

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        try {
            FileInputStream(file).use { fis ->
                DataInputStream(fis).use { dis ->
                    val magic = dis.readInt()
                    val version = dis.readInt()
                    val dim = dis.readInt()
                    val count = dis.readInt()

                    if (magic != MAGIC || version != VERSION) {
                        Log.w(TAG, "Unknown format: magic=$magic version=$version")
                        return@withContext false
                    }

                    chunks.clear()
                    idIndex.clear()

                    for (i in 0 until count) {
                        val srcLen = dis.readInt()
                        val srcBytes = ByteArray(srcLen).also { dis.readFully(it) }
                        val sourcePath = String(srcBytes, Charsets.UTF_8)

                        val textLen = dis.readInt()
                        val textBytes = ByteArray(textLen).also { dis.readFully(it) }
                        val text = String(textBytes, Charsets.UTF_8)

                        val embedding = FloatArray(dim).also { floats ->
                            for (j in floats.indices) floats[j] = dis.readFloat()
                        }

                        val id = "${sourcePath}:$i"
                        val idx = chunks.size
                        chunks.add(Chunk(id, sourcePath, text, embedding))
                        idIndex[id] = idx
                    }
                    Log.i(TAG, "Loaded $count chunks (dim=$dim)")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vector store: ${e.message}", e)
            false
        }
    }

    suspend fun save() = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(file).use { fos ->
                DataOutputStream(fos).use { dos ->
                    val dim = if (chunks.isEmpty()) config.embeddingDim else chunks[0].embedding.size
                    dos.writeInt(MAGIC)
                    dos.writeInt(VERSION)
                    dos.writeInt(dim)
                    dos.writeInt(chunks.size)

                    for ((idx, chunk) in chunks.withIndex()) {
                        val srcBytes = chunk.sourcePath.toByteArray(Charsets.UTF_8)
                        dos.writeInt(srcBytes.size)
                        dos.write(srcBytes)

                        val textBytes = chunk.text.toByteArray(Charsets.UTF_8)
                        dos.writeInt(textBytes.size)
                        dos.write(textBytes)

                        for (v in chunk.embedding) dos.writeFloat(v)
                    }
                }
            }
            Log.i(TAG, "Saved ${chunks.size} chunks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vector store: ${e.message}", e)
        }
    }

    fun insert(sourcePath: String, text: String, embedding: FloatArray): String {
        val id = "${sourcePath}:${chunks.size}"
        val idx = chunks.size
        chunks.add(Chunk(id, sourcePath, text, embedding))
        idIndex[id] = idx
        return id
    }

    fun search(query: FloatArray, topK: Int = config.topK): List<Pair<String, Float>> {
        if (chunks.isEmpty() || query.isEmpty()) return emptyList()

        val queryNorm = norm(query)
        if (queryNorm == 0f) return emptyList()

        val scored = chunks.map { chunk ->
            val sim = cosineSimilarity(query, chunk.embedding, queryNorm)
            chunk.text to sim
        }

        return scored
            .sortedByDescending { it.second }
            .take(topK)
    }

    fun delete(id: String): Boolean {
        val idx = idIndex[id] ?: return false
        chunks.removeAt(idx)
        rebuildIndex()
        return true
    }

    fun clear() {
        chunks.clear()
        idIndex.clear()
    }

    private fun rebuildIndex() {
        idIndex.clear()
        for ((i, chunk) in chunks.withIndex()) {
            idIndex[chunk.id] = i
        }
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray, aNorm: Float): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var bNorm = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            bNorm += b[i] * b[i]
        }
        bNorm = sqrt(bNorm)
        return if (aNorm == 0f || bNorm == 0f) 0f else dot / (aNorm * bNorm)
    }
}
