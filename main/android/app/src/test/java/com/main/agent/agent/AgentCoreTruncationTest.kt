package com.main.agent.agent

import android.content.Context
import com.main.agent.llm.DeviceCapability
import com.main.agent.llm.LlamaEngine
import com.main.agent.tools.base.ToolRegistry
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoreTruncationTest {

    private fun buildAgentCore(recommendedCtx: Int): AgentCore {
        val capability = DeviceCapability.Info(
            tier = DeviceCapability.Tier.LOW,
            totalRamMb = 2048L,
            availRamMb = 1024L,
            cpuCores = 4,
            hasVulkan = false,
            recommendedCtx = recommendedCtx,
            recommendedThreads = 4,
            maxModelTier = DeviceCapability.ModelTier.SMALL,
        )
        return AgentCore(
            context = mockk<Context>(relaxed = true),
            engine = mockk<LlamaEngine>(relaxed = true),
            capability = capability,
            toolRegistry = mockk<ToolRegistry>(relaxed = true),
            router = mockk<AgentRouter>(relaxed = true),
            reactLoop = mockk<ReActLoop>(relaxed = true),
        )
    }

    @Test
    fun `truncateHistoryToTokenBudget drops oldest turns and keeps the most recent`() {
        val agentCore = buildAgentCore(recommendedCtx = 512)
        val fullSystem = "system prompt"
        val userMessage = "hi"
        val history = (1..50).map { "user" to "turn number $it ".repeat(50) }

        val result = agentCore.truncateHistoryToTokenBudget(history, fullSystem, userMessage)

        assertTrue(result.size < history.size)
        assertEquals(history.last(), result.last())
    }

    @Test
    fun `truncateHistoryToTokenBudget keeps everything when history fits comfortably`() {
        val agentCore = buildAgentCore(recommendedCtx = 8192)
        val fullSystem = "system prompt"
        val userMessage = "hi"
        val history = listOf(
            "user" to "hello",
            "assistant" to "hi there",
            "user" to "how are you",
        )

        val result = agentCore.truncateHistoryToTokenBudget(history, fullSystem, userMessage)

        assertEquals(history.size, result.size)
    }
}
