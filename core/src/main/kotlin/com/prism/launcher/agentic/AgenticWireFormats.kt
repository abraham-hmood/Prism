package com.prism.launcher.agentic

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * Wire format for backends with native structured tool-calling (OpenAI-compatible Cloud
 * endpoints, and Ollama's `/api/chat`, which deliberately cloned the same `tools`/`tool_calls`
 * JSON shape). One shared adapter for both since the shape only differs in minor, tolerated
 * ways (e.g. Ollama's `function.arguments` comes back as a JSON object rather than a
 * JSON-encoded string, and Ollama's tool_calls usually omit an `id`).
 */
object NativeToolFormat {

    fun buildToolsJsonArray(tools: List<ToolDefinition>): JSONArray {
        val array = JSONArray()
        tools.forEach { tool ->
            val function = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", tool.parametersSchema)
            array.put(JSONObject().put("type", "function").put("function", function))
        }
        return array
    }

    /**
     * [argumentsAsObject] controls how a tool call's arguments are re-serialized when echoing an
     * assistant turn back into the request history for the next round: OpenAI's spec wants a
     * JSON-encoded string (the default, used for Cloud); Ollama's native `/api/chat` wants a raw
     * JSON object there instead and 400s ("looks like object but can't find closing '}'") if it
     * gets a string -- even though its own *responses* tolerate either shape (see [parseMessage]).
     */
    fun buildMessagesJsonArray(messages: List<AgenticMessage>, argumentsAsObject: Boolean = false): JSONArray {
        val array = JSONArray()
        messages.forEach { msg ->
            val obj = JSONObject().put("role", msg.role).put("content", msg.content)
            if (!msg.toolCalls.isNullOrEmpty()) {
                val callsArray = JSONArray()
                msg.toolCalls.forEach { call ->
                    val argumentsValue: Any = if (argumentsAsObject) {
                        try { JSONObject(call.argumentsJson) } catch (e: Exception) { call.argumentsJson }
                    } else {
                        call.argumentsJson
                    }
                    val function = JSONObject().put("name", call.name).put("arguments", argumentsValue)
                    val callObj = JSONObject().put("type", "function").put("function", function)
                    if (call.callId != null) callObj.put("id", call.callId)
                    // Echo back any opaque vendor blob exactly as received (see ToolCall.providerExtra) --
                    // e.g. required by Gemini 3's thought-signature validation on multi-turn tool calls.
                    if (call.providerExtra != null) callObj.put("extra_content", call.providerExtra)
                    callsArray.put(callObj)
                }
                obj.put("tool_calls", callsArray)
            }
            if (msg.toolCallId != null) obj.put("tool_call_id", msg.toolCallId)
            if (msg.toolName != null) obj.put("name", msg.toolName)
            array.put(obj)
        }
        return array
    }

    /** Parses a `message` object from a chat-completion response into a [ModelTurn]. */
    fun parseMessage(messageJson: JSONObject): ModelTurn {
        val toolCallsJson = messageJson.optJSONArray("tool_calls")
        if (toolCallsJson != null && toolCallsJson.length() > 0) {
            val calls = (0 until toolCallsJson.length()).mapNotNull { i ->
                val callObj = toolCallsJson.optJSONObject(i) ?: return@mapNotNull null
                val function = callObj.optJSONObject("function") ?: return@mapNotNull null
                val name = function.optString("name").ifBlank { return@mapNotNull null }
                // OpenAI encodes arguments as a JSON string; Ollama returns them as a raw object.
                val argsRaw = function.opt("arguments")
                val argumentsJson = when (argsRaw) {
                    is JSONObject -> argsRaw.toString()
                    is String -> argsRaw
                    else -> "{}"
                }
                val callId = if (callObj.has("id")) callObj.getString("id") else null
                val providerExtra = callObj.optJSONObject("extra_content")
                ToolCall(callId = callId, name = name, argumentsJson = argumentsJson, providerExtra = providerExtra)
            }
            if (calls.isNotEmpty()) return ModelTurn.Calls(calls)
        }
        return ModelTurn.Answer(messageJson.optString("content", ""))
    }
}

/**
 * Wire format for models with no native tool-calling API at all (local/GGUF, or any backend a
 * user has explicitly assigned a custom [AgenticSyntaxEntity] to because it doesn't speak the
 * standard `tools` JSON either). Tool availability is described via prompt injection, and calls
 * are recognized in the model's raw text output via a user-supplied regex.
 */
object SyntaxToolFormat {

    fun renderSystemPrompt(syntax: AgenticSyntaxEntity, tools: List<ToolDefinition>): String {
        val rendered = tools.joinToString("\n") { tool ->
            syntax.toolFormatTemplate
                .replace("{{name}}", tool.name)
                .replace("{{description}}", tool.description)
                .replace("{{parameters}}", tool.parametersSchema.toString())
        }
        return syntax.systemPromptTemplate.replace("{{tools}}", rendered)
    }

    /** Finds every regex match in [rawText] and parses each capture group as a
     * `{"name":"...","arguments":{...}}` object. Malformed matches are silently skipped rather
     * than aborting -- same tolerant-parsing convention CloudAiService uses for streaming. */
    fun extractToolCalls(syntax: AgenticSyntaxEntity, rawText: String): List<ToolCall> {
        val regex = try { Regex(syntax.callExtractionRegex, RegexOption.DOT_MATCHES_ALL) } catch (e: Exception) { return emptyList() }
        return regex.findAll(rawText).mapNotNull { match ->
            val jsonText = match.groupValues.getOrNull(1) ?: return@mapNotNull null
            try {
                val obj = JSONObject(jsonText)
                val name = obj.optString("name").ifBlank { return@mapNotNull null }
                val arguments = obj.optJSONObject("arguments") ?: JSONObject()
                ToolCall(callId = null, name = name, argumentsJson = arguments.toString())
            } catch (e: Exception) {
                null
            }
        }.toList()
    }

    /** Removes every regex match so a mixed prose+tool-call generation doesn't show raw
     * tool-call markup to the user once a final answer is displayed. */
    fun stripToolCallSpans(syntax: AgenticSyntaxEntity, rawText: String): String {
        val regex = try { Regex(syntax.callExtractionRegex, RegexOption.DOT_MATCHES_ALL) } catch (e: Exception) { return rawText }
        return regex.replace(rawText, "").trim()
    }
}
