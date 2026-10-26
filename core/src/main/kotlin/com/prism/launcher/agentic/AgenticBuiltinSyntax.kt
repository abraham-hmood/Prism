package com.prism.launcher.agentic

/**
 * The tool-calling profile Prism uses when the backend has no native tool support of its own.
 *
 * ## Why this exists
 *
 * A tool the model was never told about does not exist. Cloud endpoints and Ollama answer with a
 * real `tools` field, so Prism hands them every tool's name, description and argument schema in
 * the request. A local GGUF model has no such field: before this, running one with agentic tools
 * on meant the tools were simply never mentioned, and the model answered as if Prism had none.
 *
 * This profile is the same information in the same shape, injected into the prompt instead. It is
 * the fallback rather than the rule: an imported [AgenticSyntaxEntity] always wins, because a user
 * may know their model's house style better than Prism does.
 *
 * It is also what [SyntaxToolFormat] renders, so every backend -- cloud, Ollama, local -- receives
 * the three things a model needs to use a tool: what it is called, what it does, and what arguments
 * it takes.
 */
object AgenticBuiltinSyntax {

    /**
     * Stable id, so a user can select this profile explicitly and the engine can recognise it
     * without a database row behind it.
     */
    const val ID = "prism-default"

    const val NAME = "Prism default"

    val DEFAULT: AgenticSyntaxEntity = AgenticSyntaxEntity(
        id = ID,
        name = NAME,
        systemPromptTemplate = """
            You can take real actions on this device by calling tools.

            Every tool below is described by its name, what it does, and the arguments it takes.
            Call one when it is actually needed to answer or to act; its result is returned to you
            and you carry on from there. Do not invent a result, and do not call a tool with an
            argument you had to guess -- ask instead, or use the tool that finds the answer.

            Available tools:
            {{tools}}

            To call exactly one tool, reply with this block and nothing else:

            <tool_call>{"name": "the_tool_name", "arguments": {"argument": "value"}}</tool_call>

            "arguments" is always a JSON object; use {} for a tool that takes none. Make one call
            and wait for its result before making the next. To answer without using a tool, reply
            normally with no block at all.
        """.trimIndent(),
        toolFormatTemplate = "- {{name}}: {{description}}\n  Arguments (JSON Schema): {{parameters}}",
        // THE WHOLE PAYLOAD BETWEEN THE TAGS, not just the outer braces: a lazy `\{.*?\}` match
        // stops at the first closing brace inside "arguments" and hands the parser half an object.
        // Lazy `.*?` between the tags still separates two calls in one reply.
        callExtractionRegex = "<tool_call>\\s*(.*?)\\s*</tool_call>",
    )
}
