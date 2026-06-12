package com.main.agent.rag

/** User-configurable vector DB settings, persisted via UserPreferences. */
data class VectorDbConfig(
    val chunkSize:       Int     = 512,    // tokens per chunk
    val chunkOverlapPct: Int     = 10,     // % overlap between chunks
    val topK:            Int     = 5,      // default retrieval count
    val distanceMetric:  Metric  = Metric.COSINE,
    val embeddingDim:    Int     = 768,    // nomic-embed-text-v1.5 dimension
    val maxIndexedFiles: Int     = 500,
) {
    enum class Metric { COSINE, L2 }
}
