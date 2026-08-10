package com.prism.launcher.agentic

import com.prism.core.json.JSONObject

/** A tool's interface description + how Prism should execute it. Canonical, backend-agnostic. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
    val executor: ToolExecutorConfig
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
