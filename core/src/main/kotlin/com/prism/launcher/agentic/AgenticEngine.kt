package com.prism.launcher.agentic

import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService
import com.prism.launcher.messaging.LocalAi

/**
 * Backend-agnostic tool-calling loop: model responds -> if it requested tool calls, run them for
 * real and feed the results back in -> re-invoke -> repeat until a plain final answer (or
 * [MAX_ITERATIONS] is hit). Each backend adapter ([NativeToolFormat] for Cloud/Ollama,
 * [SyntaxToolFormat] for local/GGUF or any backend assigned a custom syntax) only has to
 * translate to/from the canonical [ToolDefinition]/[ToolCall]/[ModelTurn] types up front; this
 * loop never needs to know which backend it's talking to.
 *
 * Entry point is [AiManager.getResponse], which delegates here when agentic tools are enabled
 * and no image is attached (multimodal + tool-calling in the same turn isn't supported in v1).
 */
object AgenticEngine {

    private const val MAX_ITERATIONS = 5

    suspend fun run(
        userText: String,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): Pair<String, Pair<String?, String?>> {
        val dao = AppDatabase.get().agenticDao()
        val tools = dao.getEnabledTools().map { it.toDefinition() } + AgenticBuiltinTools.ALL

        val syntaxId = PrismSettings.getActiveAgenticSyntaxId()
        val syntax = syntaxId?.let { dao.getSyntax(it) }

        val mode = PrismSettings.getAiMode()
        return when (mode) {
            PrismSettings.AI_MODE_CLOUD -> runCloud(userText, tools, syntax, onToken, onReasoning)
            PrismSettings.AI_MODE_LOCAL_CLOUD -> runOllama(userText, tools, syntax, onToken, onReasoning)
            else -> {
                // No local model has a native tool-calling API -- without a syntax profile there's
                // nothing to inject/parse, so just fall back to a single plain generation.
                if (syntax == null) {
                    plainLocal(userText, onToken, onReasoning)
                } else {
                    runLocal(userText, tools, syntax, onToken, onReasoning)
                }
            }
        }
    }

    private suspend fun runCloud(
        userText: String,
        tools: List<ToolDefinition>,
        syntax: AgenticSyntaxEntity?,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): Pair<String, Pair<String?, String?>> {
        val cloudModel = PrismSettings.getActiveCloudModel()
            ?: return Pair("Error: No cloud model selected. Add one in Settings > AI Engine > Manage Cloud Models.", Pair(null, null))

        val toolsJson = if (syntax == null) NativeToolFormat.buildToolsJsonArray(tools) else null
        val messages = mutableListOf<AgenticMessage>()
        if (syntax != null) messages.add(AgenticMessage("system", SyntaxToolFormat.renderSystemPrompt(syntax, tools)))
        messages.add(AgenticMessage("user", userText))

        repeat(MAX_ITERATIONS) {
            val messageJson = CloudAiService.fetchChatWithTools(
                cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId,
                NativeToolFormat.buildMessagesJsonArray(messages), toolsJson
            )
            val turn = interpretTurn(messageJson.optString("content", ""), messageJson, syntax)

            when (turn) {
                is ModelTurn.Answer -> {
                    onToken?.invoke(turn.text)
                    return Pair(turn.text, Pair(null, null))
                }
                is ModelTurn.Calls -> {
                    onReasoning?.invoke(describeCalls(turn.calls))
                    messages.add(AgenticMessage("assistant", "", toolCalls = turn.calls))
                    turn.calls.forEach { call -> messages.add(resultMessage(tools, call)) }
                }
            }
        }
        val fallback = "I tried using tools but couldn't reach a final answer after $MAX_ITERATIONS steps."
        onToken?.invoke(fallback)
        return Pair(fallback, Pair(null, null))
    }

    private suspend fun runOllama(
        userText: String,
        tools: List<ToolDefinition>,
        syntax: AgenticSyntaxEntity?,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): Pair<String, Pair<String?, String?>> {
        val endpoint = PrismSettings.getSelectedOllamaEndpoint()
            ?: return Pair("Error: No Ollama server selected. Scan and pick one in Settings.", Pair(null, null))

        val toolsJson = if (syntax == null) NativeToolFormat.buildToolsJsonArray(tools) else null
        val messages = mutableListOf<AgenticMessage>()
        if (syntax != null) messages.add(AgenticMessage("system", SyntaxToolFormat.renderSystemPrompt(syntax, tools)))
        messages.add(AgenticMessage("user", userText))

        repeat(MAX_ITERATIONS) {
            val messageJson = CloudAiService.fetchOllamaChatWithTools(
                endpoint.host, endpoint.port, endpoint.model,
                NativeToolFormat.buildMessagesJsonArray(messages, argumentsAsObject = true), toolsJson
            )
            val turn = interpretTurn(messageJson.optString("content", ""), messageJson, syntax)

            when (turn) {
                is ModelTurn.Answer -> {
                    onToken?.invoke(turn.text)
                    return Pair(turn.text, Pair(null, null))
                }
                is ModelTurn.Calls -> {
                    onReasoning?.invoke(describeCalls(turn.calls))
                    messages.add(AgenticMessage("assistant", "", toolCalls = turn.calls))
                    turn.calls.forEach { call -> messages.add(resultMessage(tools, call)) }
                }
            }
        }
        val fallback = "I tried using tools but couldn't reach a final answer after $MAX_ITERATIONS steps."
        onToken?.invoke(fallback)
        return Pair(fallback, Pair(null, null))
    }

    private suspend fun runLocal(
        userText: String,
        tools: List<ToolDefinition>,
        syntax: AgenticSyntaxEntity,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): Pair<String, Pair<String?, String?>> {
        val modelPath = PrismSettings.getLocalAiModelPath()
        if (modelPath.isBlank()) return Pair("Error: No local model loaded. Import one in Settings.", Pair(null, null))

        // Local models have no message-history API -- the running exchange is kept as a single
        // growing plain-text transcript instead, re-sent as the whole "prompt" each round.
        val transcript = StringBuilder(SyntaxToolFormat.renderSystemPrompt(syntax, tools))
            .append("\n\nUser: ").append(userText).append("\n")

        repeat(MAX_ITERATIONS) {
            val raw = LocalAi.generator.generate(modelPath, transcript.toString())
            val calls = SyntaxToolFormat.extractToolCalls(syntax, raw)
            if (calls.isEmpty()) {
                val answer = SyntaxToolFormat.stripToolCallSpans(syntax, raw)
                onToken?.invoke(answer)
                return Pair(answer, Pair(null, null))
            }
            onReasoning?.invoke(describeCalls(calls))
            transcript.append("Assistant: ").append(raw).append("\n")
            calls.forEach { call ->
                val tool = tools.find { it.name == call.name }
                val result = if (tool == null) "Error: unknown tool '${call.name}'"
                             else AgenticToolExecutor.execute(tool, call.argumentsJson)
                transcript.append("Tool result (${call.name}): ").append(result).append("\n")
            }
        }
        val fallback = "I tried using tools but couldn't reach a final answer after $MAX_ITERATIONS steps."
        onToken?.invoke(fallback)
        return Pair(fallback, Pair(null, null))
    }

    private suspend fun plainLocal(
        userText: String,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): Pair<String, Pair<String?, String?>> {
        val modelPath = PrismSettings.getLocalAiModelPath()
        if (modelPath.isBlank()) return Pair("Error: No local model loaded. Import one in Settings.", Pair(null, null))
        val text = if (onToken != null && PrismSettings.getStreamingEnabled()) {
            LocalAi.generator.generateStreaming(modelPath, userText, PrismSettings.getMaxTokens(), onToken, onReasoning)
        } else {
            LocalAi.generator.generate(modelPath, userText)
        }
        return Pair(text, Pair(null, null))
    }

    /** Native backends (no syntax) interpret [messageJson] directly; backends with a syntax
     * assigned always parse tool calls out of the raw text via regex instead, even for Cloud/Ollama
     * (covers non-standard "OpenAI-compatible" endpoints that don't actually support `tools`). */
    private fun interpretTurn(rawText: String, messageJson: com.prism.core.json.JSONObject, syntax: AgenticSyntaxEntity?): ModelTurn {
        if (syntax != null) {
            val calls = SyntaxToolFormat.extractToolCalls(syntax, rawText)
            return if (calls.isNotEmpty()) ModelTurn.Calls(calls) else ModelTurn.Answer(SyntaxToolFormat.stripToolCallSpans(syntax, rawText))
        }
        return NativeToolFormat.parseMessage(messageJson)
    }

    private suspend fun resultMessage(tools: List<ToolDefinition>, call: ToolCall): AgenticMessage {
        val tool = tools.find { it.name == call.name }
        val result = if (tool == null) "Error: unknown tool '${call.name}'"
                     else AgenticToolExecutor.execute(tool, call.argumentsJson)
        return AgenticMessage("tool", result, toolCallId = call.callId, toolName = call.name)
    }

    private fun describeCalls(calls: List<ToolCall>): String =
        "Calling ${calls.joinToString(", ") { it.name }}..."
}
