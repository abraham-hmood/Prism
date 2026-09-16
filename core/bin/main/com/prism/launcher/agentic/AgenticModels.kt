package com.prism.launcher.agentic

import com.prism.core.json.JSONObject

/** A tool's interface description + how Prism should execute it. Canonical, backend-agnostic. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
    val executor: ToolExecutorConfig,
    /** True for tools that must never be offered to (or run by) a model unless
     * [com.prism.core.PrismPlatform.host.hasActiveCellularLine] is true -- see
     * [com.prism.launcher.agentic.AgenticBuiltinTools]'s read_text/send_text/make_call. Checked
     * in two places: [com.prism.launcher.agentic.AgenticEngine.run] excludes such a tool from the
     * list a model is ever shown, and the tool's own handler re-checks before acting, so neither
     * a model working around the omission nor a direct call from elsewhere (the tools page's
     * "test" button) can bypass it. */
    val requiresActiveLine: Boolean = false,
    /** True for tools that must never be offered to (or run by) a model unless
     * [com.prism.launcher.PrismSettings.hasImageGenerator] is true -- see
     * [com.prism.launcher.agentic.AgenticBuiltinTools]'s generate_image. Gated in exactly the same
     * three places as [requiresActiveLine]: [AgenticEngine.run] omits the tool from what a model
     * is shown, the tool's own handler re-checks before acting, and the Agentic Tools page renders
     * it red and unclickable. */
    val requiresImageGenerator: Boolean = false
)

sealed class ToolExecutorConfig {
    data class Http(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val bodyTemplate: String
    ) : ToolExecutorConfig()

    data class Builtin(val id: String) : ToolExecutorConfig()
}

/** A model's request to call a tool, parsed out of whatever wire format the backend used. */
data class ToolCall(
    val callId: String?,
    val name: String,
    val argumentsJson: String,
    // Opaque vendor extension blob some providers attach to a tool_call (e.g. Gemini 3's
    // `extra_content.google.thought_signature`, required back verbatim on the same call in the
    // next request or the API 400s). Never interpreted here -- just captured and echoed back on
    // this exact call so no per-provider branching leaks into the universal tool-calling engine.
    val providerExtra: JSONObject? = null
)

/** The result of actually running a [ToolCall], fed back to the model as its next turn. */
data class ToolResult(
    val callId: String?,
    val name: String,
    val content: String
)

/** What the model did on one turn: either a final answer, or one or more tool calls to run. */
sealed class ModelTurn {
    data class Answer(val text: String) : ModelTurn()
    data class Calls(val calls: List<ToolCall>) : ModelTurn()
}

/**
 * One turn in the short-lived tool-calling exchange built up and consumed entirely within a
 * single [AgenticEngine.run] call -- this is a local scratchpad for the tool-call round-trip,
 * not the app's persisted chat history (which nothing in this codebase threads across turns
 * today; see AiManager).
 */
data class AgenticMessage(
    val role: String, // "system" | "user" | "assistant" | "tool"
    val content: String,
    val toolCalls: List<ToolCall>? = null, // only set on role="assistant" turns that called tools
    val toolCallId: String? = null,        // only set on role="tool" turns (which call this answers)
    val toolName: String? = null           // only set on role="tool" turns (Ollama keys by name, not id)
)

fun AgenticToolEntity.toDefinition(): ToolDefinition {
    val schema = try {
        JSONObject(parametersJson)
    } catch (e: Exception) {
        JSONObject("{\"type\":\"object\",\"properties\":{}}")
    }
    val headers: Map<String, String> = try {
        val obj = JSONObject(httpHeadersJson)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }
    return ToolDefinition(
        name = name,
        description = description,
        parametersSchema = schema,
        executor = ToolExecutorConfig.Http(httpMethod, httpUrl, headers, httpBodyTemplate)
    )
}
