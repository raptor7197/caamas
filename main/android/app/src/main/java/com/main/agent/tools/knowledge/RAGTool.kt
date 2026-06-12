package com.main.agent.tools.knowledge

import android.content.Context
import com.main.agent.rag.RAGRetriever
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import com.main.agent.tools.base.buildJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RAGTool : Tool {
    override val name        = "read_rag"
    override val description = "Search your personal knowledge base (files in the agent folder) for relevant information."
    override val schema = """{"type":"function","function":{"name":"read_rag","description":"$description",
        "parameters":{"type":"object","properties":{
        "query":{"type":"string","description":"What to search for in the knowledge base"},
        "top_k":{"type":"integer","description":"Number of results (1-10)","default":5}}
        ,"required":["query"]}}}"""

    var retriever: RAGRetriever? = null

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val r = retriever
        if (r == null) {
            return ToolResult.Error(
                "RAG not initialized. Configure an agent folder in Settings and ensure the embedding model is downloaded."
            )
        }

        val query = args["query"]?.jsonPrimitive?.content ?: return ToolResult.Error(
            "Missing 'query' argument", ToolResult.ErrorCode.PARSE_ERROR
        )
        if (query.isBlank()) return ToolResult.Error(
            "Query is empty", ToolResult.ErrorCode.PARSE_ERROR
        )

        val topK = (args["top_k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)

        val results = r.retrieve(query, topK)
        if (results.isEmpty()) {
            return ToolResult.Error("No relevant information found", ToolResult.ErrorCode.NO_RESULTS)
        }

        val content = results.joinToString("\n---\n")
        return ToolResult.Success(
            buildJsonString {
                put("results_count", results.size)
                put("content", content)
            }
        )
    }
}
