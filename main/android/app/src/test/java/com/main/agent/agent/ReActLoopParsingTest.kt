package com.main.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReActLoopParsingTest {

    @Test
    fun `TOOL_CALL_RE extracts a single tool call`() {
        val input = "some text [TOOL_CALL]{\"name\":\"calculator\",\"args\":{}}[/TOOL_CALL] more text"

        val matches = TOOL_CALL_RE.findAll(input).toList()

        assertEquals(1, matches.size)
        assertEquals("{\"name\":\"calculator\",\"args\":{}}", matches[0].groupValues[1])
    }

    @Test
    fun `TOOL_CALL_RE extracts multiple tool calls`() {
        val input = "[TOOL_CALL]{\"name\":\"calculator\",\"args\":{}}[/TOOL_CALL]" +
            " middle " +
            "[TOOL_CALL]{\"name\":\"get_weather\",\"args\":{}}[/TOOL_CALL]"

        val matches = TOOL_CALL_RE.findAll(input).toList()

        assertEquals(2, matches.size)
    }

    @Test
    fun `TOOL_CALL_RE finds nothing in plain text`() {
        val input = "just a plain response with no tool markers at all"

        val matches = TOOL_CALL_RE.findAll(input).toList()

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `capToolResult returns input unchanged when under the length limit`() {
        val input = "short tool result"

        val result = capToolResult(input)

        assertEquals(input, result)
    }

    @Test
    fun `capToolResult truncates and appends marker when input exceeds the limit`() {
        val input = "a".repeat(MAX_TOOL_RESULT_CHARS + 100)

        val result = capToolResult(input)

        assertEquals("a".repeat(MAX_TOOL_RESULT_CHARS) + "\n...[truncated]", result)
    }
}
