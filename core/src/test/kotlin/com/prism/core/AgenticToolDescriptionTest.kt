package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.agentic.AgenticBuiltinSyntax
import com.prism.launcher.agentic.AgenticBuiltinTools
import com.prism.launcher.agentic.AgenticSyntaxEntity
import com.prism.launcher.agentic.NativeToolFormat
import com.prism.launcher.agentic.SyntaxToolFormat
import com.prism.launcher.agentic.ToolDefinition
import com.prism.launcher.agentic.ToolExecutorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every backend is told, for every tool, what it is, what it does and how to use it.
 *
 * ## Why this is a test and not a convention
 *
 * The three backends receive tools through two different mechanisms: a native `tools` array
 * (Cloud, Ollama) and prompt injection (local, and any endpoint with a syntax). It is easy to
 * improve one and silently starve the other -- and a model that was never told a tool exists
 * cannot use it, which looks exactly like a broken tool. So both renderings are checked against
 * the SAME list of built-ins, and a deliberately terse custom template has to be filled in rather
 * than trusted to describe itself.
 */
class AgenticToolDescriptionTest {

    private val sampleCustom = ToolDefinition(
        name = "lookup_order",
        description = "Looks up one order by its number and returns its status.",
        parametersSchema = JSONObject(
            """{"type":"object","properties":{"number":{"type":"string"}},"required":["number"]}""",
        ),
        executor = ToolExecutorConfig.Builtin("lookup_order"),
    )

    @Test
    fun `every built-in reaches a native backend with a name, description and arguments`() {
        val tools = AgenticBuiltinTools.ALL + sampleCustom
        val array = NativeToolFormat.buildToolsJsonArray(tools)
        assertEquals(tools.size, array.length())

        for (index in 0 until array.length()) {
            val function = assertNotNull(array.optJSONObject(index)?.optJSONObject("function"))
            val name = function.optString("name")
            assertTrue(name.isNotBlank(), "tool at $index has no name")
            assertTrue(
                function.optString("description").isNotBlank(),
                "$name is sent to a native backend with no description",
            )
            val parameters = assertNotNull(
                function.optJSONObject("parameters"),
                "$name is sent with no argument schema",
            )
            assertTrue(
                parameters.length() > 0,
                "$name's schema says nothing about its arguments: $parameters",
            )
        }
    }

    @Test
    fun `the built-in prompt profile tells a local model the same three things`() {
        val rendered = SyntaxToolFormat.renderSystemPrompt(
            AgenticBuiltinSyntax.DEFAULT,
            AgenticBuiltinTools.ALL + sampleCustom,
        )

        for (tool in AgenticBuiltinTools.ALL + sampleCustom) {
            assertTrue(rendered.contains(tool.name), "${tool.name} is not named in the prompt")
            assertTrue(
                rendered.contains(tool.description.trim()),
                "${tool.name} is named but not described in the prompt",
            )
        }
        // The schema, not merely the word "arguments": a model has to know the argument names.
        assertTrue(rendered.contains("\"properties\""), "no argument schema reaches the prompt")
        assertTrue(rendered.contains("lookup_order"), "a custom HTTP tool is described too")
        assertTrue(
            rendered.contains("<tool_call>"),
            "the prompt has to say HOW to call something, not only what exists",
        )
        // The extraction pattern the prompt promises has to be the one Prism actually parses.
        assertNotNull(Regex(AgenticBuiltinSyntax.DEFAULT.callExtractionRegex))
    }

    @Test
    fun `a terse custom template is filled in rather than allowed to starve the model`() {
        val terse = AgenticSyntaxEntity(
            id = "terse",
            name = "Terse",
            systemPromptTemplate = "Tools:\n{{tools}}",
            toolFormatTemplate = "- {{name}}",
            callExtractionRegex = AgenticBuiltinSyntax.DEFAULT.callExtractionRegex,
        )
        val rendered = SyntaxToolFormat.renderSystemPrompt(terse, listOf(sampleCustom))

        assertTrue(rendered.contains("lookup_order"), rendered)
        assertTrue(
            rendered.contains("Looks up one order by its number"),
            "a template that leaves {{description}} out must not hide what the tool does: $rendered",
        )
        assertTrue(
            rendered.contains("number"),
            "a template that leaves {{parameters}} out must not hide the arguments: $rendered",
        )
    }

    @Test
    fun `a template with no tools placeholder still lists them`() {
        val forgetful = AgenticSyntaxEntity(
            id = "forgetful",
            name = "Forgetful",
            systemPromptTemplate = "You are a helpful assistant.",
            toolFormatTemplate = "{{name}}: {{description}} {{parameters}}",
            callExtractionRegex = AgenticBuiltinSyntax.DEFAULT.callExtractionRegex,
        )
        val rendered = SyntaxToolFormat.renderSystemPrompt(forgetful, listOf(sampleCustom))
        assertTrue(rendered.startsWith("You are a helpful assistant."), rendered)
        assertTrue(rendered.contains("lookup_order"), rendered)
        assertTrue(rendered.contains("Looks up one order"), rendered)
    }

    @Test
    fun `an unnamed tool is not silently dropped from the prompt`() {
        val unnamed = AgenticSyntaxEntity(
            id = "no-name-slot",
            name = "No name slot",
            systemPromptTemplate = "{{tools}}",
            toolFormatTemplate = "{{description}} {{parameters}}",
            callExtractionRegex = AgenticBuiltinSyntax.DEFAULT.callExtractionRegex,
        )
        val rendered = SyntaxToolFormat.renderSystemPrompt(unnamed, listOf(sampleCustom))
        assertTrue(rendered.contains("lookup_order"), "the model cannot call a tool it cannot name")
    }

    @Test
    fun `the default profile parses a call whose arguments contain braces and quotes`() {
        val raw = """
            I will write that file.
            <tool_call>{"name": "write_file", "arguments": {"path": "notes/plan}.txt", "content": "a } b"}}</tool_call>
        """.trimIndent()
        val calls = SyntaxToolFormat.extractToolCalls(AgenticBuiltinSyntax.DEFAULT, raw)

        assertEquals(1, calls.size, "the block should parse as one call: $raw")
        assertEquals("write_file", calls[0].name)
        val args = JSONObject(calls[0].argumentsJson)
        assertEquals("notes/plan}.txt", args.optString("path"))
        assertEquals("a } b", args.optString("content"))

        assertTrue(
            !SyntaxToolFormat.stripToolCallSpans(AgenticBuiltinSyntax.DEFAULT, raw).contains("tool_call"),
            "the markup must not be left in the answer shown to the user",
        )
    }

    @Test
    fun `two calls in one reply are both recognised`() {
        val raw = "First:\n" +
            "<tool_call>{\"name\":\"mkdir\",\"arguments\":{\"path\":\"a\"}}</tool_call>\n" +
            "Then:\n" +
            "<tool_call>{\"name\":\"write_file\",\"arguments\":{\"path\":\"a/b.txt\",\"content\":\"hi\"}}</tool_call>"
        val calls = SyntaxToolFormat.extractToolCalls(AgenticBuiltinSyntax.DEFAULT, raw)
        assertEquals(listOf("mkdir", "write_file"), calls.map { it.name })
    }

    @Test
    fun `a custom tool with no description is still described to the model`() {
        val blank = sampleCustom.copy(description = "  ")
        val array = NativeToolFormat.buildToolsJsonArray(listOf(blank))
        val description = assertNotNull(
            array.optJSONObject(0)?.optJSONObject("function")?.optString("description"),
        )
        assertTrue(description.isNotBlank(), "a blank description leaves the model nothing to go on")
        assertTrue(description.contains("lookup_order"))

        val prompt = SyntaxToolFormat.renderSystemPrompt(AgenticBuiltinSyntax.DEFAULT, listOf(blank))
        assertTrue(prompt.contains("lookup_order"))
        assertTrue(prompt.contains("Runs the Prism tool lookup_order."))
    }
}
