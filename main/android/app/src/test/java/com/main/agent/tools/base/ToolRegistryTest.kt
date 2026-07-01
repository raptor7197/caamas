package com.main.agent.tools.base

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private val context = mockk<Context>(relaxed = true)
    private val registry = ToolRegistry()

    @Test
    fun `execute returns NOT_FOUND for unregistered tool name`() = runTest {
        val result = registry.execute(context, "not_a_real_tool", "{}")

        assertTrue(result is ToolResult.Error)
        assertEquals(ToolResult.ErrorCode.NOT_FOUND, (result as ToolResult.Error).errorCode)
    }

    @Test
    fun `execute returns PARSE_ERROR for malformed JSON args`() = runTest {
        val result = registry.execute(context, "calculator", "{not json")

        assertTrue(result is ToolResult.Error)
        assertEquals(ToolResult.ErrorCode.PARSE_ERROR, (result as ToolResult.Error).errorCode)
    }

    @Test
    fun `allSchemas returns a well-formed JSON array string`() {
        val schemas = registry.allSchemas()

        assertTrue(schemas.isNotBlank())
        assertTrue(schemas.startsWith("["))
        assertTrue(schemas.endsWith("]"))
    }
}
