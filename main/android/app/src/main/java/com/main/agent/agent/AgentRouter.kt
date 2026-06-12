package com.main.agent.agent

import com.main.agent.llm.DeviceCapability
import com.main.agent.llm.cloud.CloudProvider

/** Which inference backend to use for a given request. */
sealed class Route {
    /** Local small model (Gemma 2 2B). */
    object LocalSmall : Route()
    /** Local large model (Llama 3.1 8B). */
    object LocalLarge : Route()
    /** Cloud fallback (one of OpenAI / Anthropic / Mistral / Ollama). */
    data class Cloud(val provider: CloudProvider) : Route()
}

/**
 * Decides which model tier handles a given request.
 *
 * Heuristics (in priority order):
 * 1. Device is TIER_LOW → always LocalSmall, cloud if available on failure.
 * 2. Short / simple query + no tool keywords → LocalSmall.
 * 3. Multi-step, complex, or tool-heavy → LocalLarge (if TIER_HIGH).
 * 4. Cloud configured + (complexity score > 0.8 OR device is TIER_LOW) → Cloud.
 */
class AgentRouter(private val cloudProvider: CloudProvider? = null) {

    fun route(
        query:      String,
        historyLen: Int,
        capability: DeviceCapability.Info,
    ): Route {
        // Always cloud for TIER_LOW if configured
        if (capability.tier == DeviceCapability.Tier.LOW) {
            return if (cloudProvider != null && cloudProvider.isConfigured)
                Route.Cloud(cloudProvider)
            else Route.LocalSmall
        }

        val score = complexityScore(query, historyLen)

        return when {
            score >= 0.8f && cloudProvider != null && cloudProvider.isConfigured ->
                Route.Cloud(cloudProvider)
            score >= 0.4f ->
                Route.LocalLarge
            else ->
                Route.LocalSmall
        }
    }

    /**
     * Simple heuristic complexity score [0, 1].
     * Not ML-based yet — Phase 2 will replace with a tiny classifier.
     */
    private fun complexityScore(query: String, historyLen: Int): Float {
        var score = 0.0f
        val lower = query.lowercase()

        // Length proxy
        score += minOf(query.length / 300f, 0.3f)

        // Multi-step signal words
        val stepWords = listOf("then", "after", "and then", "finally", "first", "next", "also")
        score += stepWords.count { it in lower } * 0.1f

        // Tool complexity signals
        val heavyTools = listOf("send", "call", "screenshot", "download", "calendar", "schedule", "book")
        score += heavyTools.count { it in lower } * 0.15f

        // Long conversation = harder context management
        score += minOf(historyLen / 20f, 0.2f)

        // Code / math
        if (lower.contains("code") || lower.contains("function") || lower.contains("equation"))
            score += 0.2f

        return minOf(score, 1.0f)
    }
}
