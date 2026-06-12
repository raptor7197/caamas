package com.main.agent.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RAGRetriever(
    private val vectorStore: VectorStore,
    private val embedEngine: EmbeddingEngine,
    private val config:      VectorDbConfig,
) {
    suspend fun retrieve(query: String, topK: Int = config.topK): List<String> =
        withContext(Dispatchers.IO) {
            if (!embedEngine.isLoaded || vectorStore.size == 0) return@withContext emptyList()
            val qEmbed = embedEngine.embed(query) ?: return@withContext emptyList()
            vectorStore.search(qEmbed, topK).map { it.first }
        }
}
