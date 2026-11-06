package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.agentic.AgenticEngine
import com.prism.launcher.agentic.LenientToolCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A model that was never trained for tool calling still asks for tools -- in its own shape.
 *
 * Each case below is a real habit of an untuned model. They must all be read as the call they are,
 * and ordinary JSON that names no offered tool must stay an answer.
 */
class LenientToolCallsTest {

    private val tools = listOf("web_search", "read_page", "set_timer")

    private fun single(text: String): Pair<String, JSONObject> {
        val calls = LenientToolCalls.extract(text, tools)
        assertEquals(1, calls.size, "expected one call in: $text")
        return calls[0].name to JSONObject(calls[0].argumentsJson)
    }

    @Test
    fun `a fenced json block is a call`() {
        val (name, args) = single("Sure, let me look.\n```json\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"mars rover\"}}\n```")
        assertEquals("web_search", name)
        assertEquals("mars rover", args.optString("query"))
    }

    @Test
    fun `alternative field names are understood`() {
        val (name, args) = single("{\"tool\": \"Web-Search\", \"parameters\": {\"query\": \"x\"}}")
        assertEquals("web_search", name)
        assertEquals("x", args.optString("query"))
    }

    @Test
    fun `arguments written beside the name are collected`() {
        val (name, args) = single("{\"name\":\"set_timer\",\"minutes\":5}")
        assertEquals("set_timer", name)
        assertEquals(5, args.optInt("minutes"))
    }

    @Test
    fun `string-encoded arguments are decoded`() {
        val (_, args) = single("{\"function\": {\"name\": \"read_page\", \"arguments\": \"{\\\"url\\\": \\\"https://a.b\\\"}\"}}")
        assertEquals("https://a.b", args.optString("url"))
    }

    @Test
    fun `mistral and llama shapes are calls`() {
        val mistral = LenientToolCalls.extract(
            "[TOOL_CALLS] [{\"name\": \"web_search\", \"arguments\": {\"query\": \"a\"}}, {\"name\": \"read_page\", \"arguments\": {\"url\": \"b\"}}]",
            tools,
        )
        assertEquals(listOf("web_search", "read_page"), mistral.map { it.name })

        val (name, args) = single("<function=web_search>{\"query\": \"llama\"}</function>")
        assertEquals("web_search", name)
        assertEquals("llama", args.optString("query"))
    }

    @Test
    fun `a react action pair is a call`() {
        val (name, args) = single("Thought: I need to search.\nAction: web_search\nAction Input: {\"query\": \"tides\"}")
        assertEquals("web_search", name)
        assertEquals("tides", args.optString("query"))
    }

    @Test
    fun `json that names no offered tool stays an answer`() {
        val text = "Here is an example: {\"name\": \"Alice\", \"age\": 30}"
        assertTrue(LenientToolCalls.extract(text, tools).isEmpty())
        assertEquals(text, LenientToolCalls.strip(text, tools))
    }

    @Test
    fun `stripping leaves the prose`() {
        val text = "Looking it up.\n```json\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"q\"}}\n```"
        assertEquals("Looking it up.", LenientToolCalls.strip(text, tools))
    }

    @Test
    fun `a server refusing tools is told apart from other failures`() {
        assertTrue(AgenticEngine.refusedTools("Ollama Error (400): {\"error\":\"registry.ollama.ai/library/gemma2:2b does not support tools\"}"))
        assertTrue(AgenticEngine.refusedTools("Cloud Error (400): \"auto\" tool choice requires --enable-auto-tool-choice"))
        assertFalse(AgenticEngine.refusedTools("Cloud Error (401): invalid api key for tools"))
        assertFalse(AgenticEngine.refusedTools("The weather tool says it is sunny."))
    }
}
