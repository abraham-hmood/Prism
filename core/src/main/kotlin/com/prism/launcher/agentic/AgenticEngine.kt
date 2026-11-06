package com.prism.launcher.agentic

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
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
 * EVERY BACKEND IS TOLD ABOUT EVERY TOOL IT CAN CALL: name, description and argument schema.
 * Cloud and Ollama get them in the request's `tools` field; a local model has no such field and
 * gets the same three things in its prompt through [AgenticBuiltinSyntax.DEFAULT] when the user has
 * not imported a profile of their own. There is no path where tools are enabled and the model is
 * left to guess that they exist.
 *
 * ## Any text model, agentic or not
 *
 * Being "agentic" is not a property this loop requires of a model. It needs a route that takes text
 * and returns text; the tools are described in that text and the calls are read back out of it.
 *
 *  - A chat endpoint whose model refuses the native `tools` field (most small Ollama models, many
 *    OpenAI-compatible proxies) is retried with the tools in the prompt, and remembered -- see
 *    [runChat].
 *  - A model that understands the instruction but writes the call in its own shape -- a fenced JSON
 *    block, `[TOOL_CALLS]`, `<function=...>`, a ReAct "Action:" pair -- is understood anyway, via
 *    [LenientToolCalls].
 *  - Local GGUF and `.task` models, the platform's own engines (CakeChat, a mesh-hosted model) and a
 *    model on a P2P peer all run through [runPrompted].
 *
 * Entry point is [AiManager.getResponse], which delegates here when agentic tools are enabled
 * and no image is attached (multimodal + tool-calling in the same turn isn't supported in v1).
 */
object AgenticEngine {

    private const val MAX_ITERATIONS = 5

    private const val CONTEXT_INSTRUCTION =
        "Long-term context: this device has an on-device context store, and you are expected to " +
            "use it. Before the conversation or a document grows larger than you can hold, call " +
            "context_offload with the material worth keeping and keep the returned id. When you " +
            "need something you offloaded, call access_context_part with that id and a query or a " +
            "line range -- never assume you still have the whole thing in mind. Nothing in the " +
            "store is sent anywhere; it is a file on this device."

    suspend fun run(
        userText: String,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
        history: List<String> = emptyList(),
        /**
         * A text route this object cannot reach on its own -- a model hosted by a peer, which only
         * the platform knows how to call. When given, the turn runs through it with the tools
         * described in the prompt, exactly as a local model does: the route takes text and returns
         * text, and that is all a model needs to be agentic here.
         */
        complete: ((String) -> String)? = null,
    ): Pair<String, Pair<String?, String?>> {
        val dao = AppDatabase.get().agenticDao()
        // Capability-gated tools are never even offered to the model when their capability is
        // missing -- not just refused if it tries: read_text/send_text/make_call without an active
        // SIM/eSIM, generate_image without a loaded image generator. See ToolDefinition's doc
        // comments on those fields for the second, defense-in-depth check inside execute().
        val tools = (dao.getEnabledTools().map { it.toDefinition() } + AgenticBuiltinTools.ALL)
            .filter { !it.requiresActiveLine || com.prism.core.PrismPlatform.host.hasActiveCellularLine() }
            .filter { !it.requiresImageGenerator || PrismSettings.hasImageGenerator() }
            .filter { !it.requiresContextOffload || PrismSettings.getContextOffloadEnabled() }

        // Which prompt-injection profile applies, if any. "None" means the backend's native
        // `tools` field (Cloud and Ollama); the built-in profile is selectable by id and has no
        // database row, so it is resolved here rather than looked up.
        val syntaxId = PrismSettings.getActiveAgenticSyntaxId()
        val syntax = when (syntaxId) {
            null -> null
            AgenticBuiltinSyntax.ID -> AgenticBuiltinSyntax.DEFAULT
            else -> dao.getSyntax(syntaxId)
        }

        // Discards anything a previous turn left behind before this turn can pick it up.
        AgenticBuiltinTools.consumeGeneratedImageUri()

        // The tool loop starts every turn with fresh messages, so it has no memory of its own.
        // Folding the transcript in here is what stops an agentic Sam from reintroducing itself
        // every message; the local path gets it too because its transcript is rebuilt per turn.
        val prompt = foldHistory(userText, history)

        val mode = PrismSettings.getAiMode()
        val answer = when {
            // A peer's model: text in, text out, so the tools travel in the prompt.
            complete != null ->
                runPrompted(prompt, tools, syntax ?: AgenticBuiltinSyntax.DEFAULT, onToken, onReasoning, complete)
            mode == PrismSettings.AI_MODE_CLOUD -> runCloud(prompt, tools, syntax, onToken, onReasoning)
            mode == PrismSettings.AI_MODE_LOCAL_CLOUD -> runOllama(prompt, tools, syntax, onToken, onReasoning)
            // NO LOCAL BACKEND HAS NATIVE TOOL CALLING, so a local run always needs a profile.
            // The built-in one is the fallback rather than a special case: a local model with
            // agentic tools on must still be TOLD what the tools are, how to call them and what
            // they do -- otherwise the switch does nothing and the model answers as if Prism had
            // no tools at all. An imported profile still wins.
            else -> runLocal(prompt, tools, syntax ?: AgenticBuiltinSyntax.DEFAULT, onToken, onReasoning)
        }
        return answer.withGeneratedMedia()
    }

    /**
     * Attaches an image produced by `generate_image` during this turn to the turn's result.
     *
     * Applied once here rather than at each backend's return sites because a tool can only hand a
     * model back text -- without this the picture would be saved to the gallery and then silently
     * dropped, leaving Sam describing an image the conversation never shows. Backends that ran no
     * image tool are returned untouched.
     */
    private fun Pair<String, Pair<String?, String?>>.withGeneratedMedia(): Pair<String, Pair<String?, String?>> {
        val uri = AgenticBuiltinTools.consumeGeneratedImageUri() ?: return this
        return Pair(first, Pair(uri, "image"))
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
        return runChat(
            userText, tools, syntax, onToken, onReasoning,
            modelKey = PrismSettings.agenticModelKey(cloudModel.baseUrl, cloudModel.modelId),
            argumentsAsObject = false,
        ) { messages, toolsJson ->
            CloudAiService.fetchChatWithTools(cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId, messages, toolsJson)
        }
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
        return runChat(
            userText, tools, syntax, onToken, onReasoning,
            modelKey = PrismSettings.agenticModelKey("ollama://" + endpoint.host + ":" + endpoint.port, endpoint.model),
            argumentsAsObject = true,
        ) { messages, toolsJson ->
            CloudAiService.fetchOllamaChatWithTools(endpoint.host, endpoint.port, endpoint.model, messages, toolsJson)
        }
    }

    /**
     * The tool loop for any backend with a chat API -- an OpenAI-compatible endpoint, Ollama on the
     * LAN, Ollama's own cloud, anything that speaks `messages`.
     *
     * ## Native first, prompt when refused
     *
     * With no profile selected the tools go in the request's `tools` field, which is what a model
     * built for tool calling wants. A model that was NOT built for it makes its server answer 400 --
     * Ollama says "does not support tools", vLLM wants a flag, Groq says the model cannot -- and
     * before this, that refusal was shown to the user as Sam's reply. Now the same turn is retried
     * with the tools described in the prompt ([AgenticBuiltinSyntax.DEFAULT]) and the model is
     * remembered, so the next turn starts there.
     *
     * ## Two conversations, not one
     *
     * A native turn echoes calls back as `tool_calls` and results as `role: tool`. A prompted turn
     * must not: a server that refused `tools` usually refuses those roles too. So in prompted mode
     * the model's own text goes back as an assistant turn and each result arrives as a user turn,
     * which every chat endpoint accepts.
     */
    private suspend fun runChat(
        userText: String,
        tools: List<ToolDefinition>,
        syntax: AgenticSyntaxEntity?,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
        modelKey: String,
        argumentsAsObject: Boolean,
        fetch: (JSONArray, JSONArray?) -> JSONObject,
    ): Pair<String, Pair<String?, String?>> {
        var active: AgenticSyntaxEntity? = syntax
            ?: if (PrismSettings.isNativeToolsUnsupported(modelKey)) AgenticBuiltinSyntax.DEFAULT else null
        val names = tools.map { it.name }

        fun opening(): MutableList<AgenticMessage> {
            val messages = mutableListOf<AgenticMessage>()
            val prompted = active
            if (prompted != null) {
                messages.add(AgenticMessage("system", SyntaxToolFormat.renderSystemPrompt(prompted, tools) + contextInstruction()))
            } else if (PrismSettings.getContextOffloadEnabled()) {
                messages.add(AgenticMessage("system", CONTEXT_INSTRUCTION))
            }
            messages.add(AgenticMessage("user", userText))
            return messages
        }

        var messages = opening()
        var rounds = 0
        var retried = false
        while (rounds < MAX_ITERATIONS) {
            val toolsJson = if (active == null) NativeToolFormat.buildToolsJsonArray(tools) else null
            val messageJson = fetch(NativeToolFormat.buildMessagesJsonArray(messages, argumentsAsObject), toolsJson)
            val raw = messageJson.optString("content", "")

            if (active == null && !retried && refusedTools(raw)) {
                // Not counted as a round: the model has not answered yet, its server refused the shape.
                com.prism.core.PrismPlatform.log.info(
                    "Agentic", "Native tools refused for $modelKey; describing the tools in the prompt instead",
                )
                PrismSettings.markNativeToolsUnsupported(modelKey)
                active = AgenticBuiltinSyntax.DEFAULT
                retried = true
                messages = opening()
                continue
            }
            rounds++

            when (val turn = interpretTurn(raw, messageJson, active, names)) {
                is ModelTurn.Answer -> {
                    onToken?.invoke(turn.text)
                    return Pair(turn.text, Pair(null, null))
                }
                is ModelTurn.Calls -> {
                    onReasoning?.invoke(describeCalls(turn.calls))
                    val nativeCalls = messageJson.optJSONArray("tool_calls")
                    if (active == null && nativeCalls != null && nativeCalls.length() > 0) {
                        messages.add(AgenticMessage("assistant", "", toolCalls = turn.calls))
                        turn.calls.forEach { call -> messages.add(resultMessage(tools, call)) }
                    } else {
                        messages.add(AgenticMessage("assistant", raw))
                        messages.add(AgenticMessage("user", promptedResults(tools, turn.calls)))
                    }
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
        if (modelPath.isNotBlank()) {
            return runPrompted(userText, tools, syntax, onToken, onReasoning) { transcript ->
                LocalAi.generator.generate(modelPath, transcript)
            }
        }
        // No file in the local slot, but the platform may still have a text engine -- CakeChat's
        // pair, or a model on the mesh. It takes text and returns text, which is all this needs.
        val platform = com.prism.launcher.messaging.PrismText.appEngine?.takeIf { it.available() }
            ?: return Pair("Error: No local model loaded. Import one in Settings.", Pair(null, null))
        return runPrompted(userText, tools, syntax, onToken, onReasoning) { transcript ->
            platform.complete("", transcript, PrismSettings.getMaxTokens().coerceAtLeast(256), 0.7f).orEmpty()
        }
    }

    /**
     * The tool loop for anything that only takes text: a local model, a platform engine, a peer.
     *
     * Such a backend has no message-history API, so the running exchange is a single growing
     * transcript re-sent whole each round. The tools are described at the top by [syntax], and
     * calls are read back both in that syntax and in any of the shapes [LenientToolCalls] knows --
     * which is what lets a model that was never trained for tools use them anyway.
     */
    private suspend fun runPrompted(
        userText: String,
        tools: List<ToolDefinition>,
        syntax: AgenticSyntaxEntity,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
        complete: (String) -> String,
    ): Pair<String, Pair<String?, String?>> {
        val names = tools.map { it.name }
        val transcript = StringBuilder(SyntaxToolFormat.renderSystemPrompt(syntax, tools))
            .append(contextInstruction())
            .append("\n\nUser: ").append(userText).append("\n")

        repeat(MAX_ITERATIONS) {
            val raw = complete(transcript.toString())
            val calls = SyntaxToolFormat.extractToolCalls(syntax, raw)
                .ifEmpty { LenientToolCalls.extract(raw, names) }
            if (calls.isEmpty()) {
                val answer = LenientToolCalls.strip(SyntaxToolFormat.stripToolCallSpans(syntax, raw), names)
                onToken?.invoke(answer)
                return Pair(answer, Pair(null, null))
            }
            onReasoning?.invoke(describeCalls(calls))
            transcript.append("Assistant: ").append(raw).append("\n")
            transcript.append(promptedResults(tools, calls)).append("\n")
        }
        val fallback = "I tried using tools but couldn't reach a final answer after $MAX_ITERATIONS steps."
        onToken?.invoke(fallback)
        return Pair(fallback, Pair(null, null))
    }

    /** Every call's result, as text a model with no `tool` role can read. */
    private suspend fun promptedResults(tools: List<ToolDefinition>, calls: List<ToolCall>): String = buildString {
        calls.forEach { call ->
            val tool = tools.find { it.name == call.name }
            val result = if (tool == null) "Error: unknown tool '${call.name}'"
                         else AgenticToolExecutor.execute(tool, call.argumentsJson)
            append("Tool result (").append(call.name).append("): ").append(result).append('\n')
        }
        append("Use these results: call another tool if you still need one, otherwise reply with your final answer.")
    }

    /**
     * Whether a reply is the SERVER refusing the `tools` field rather than the model answering.
     *
     * Only an HTTP error that names tools or functions counts. A 401 or a 429 is a different problem,
     * and retrying it without tools would just fail again more slowly.
     */
    internal fun refusedTools(content: String): Boolean {
        val head = content.trimStart()
        if (!(head.startsWith("Cloud Error (") || head.startsWith("Ollama Error ("))) return false
        val code = head.substringAfter('(').substringBefore(')').toIntOrNull() ?: return false
        if (code == 401 || code == 403 || code == 429) return false
        val lower = head.lowercase()
        return "tool" in lower || "function" in lower
    }

    /**
     * What the model did this round.
     *
     * A backend with a syntax is read in that syntax first; a native backend is read from its
     * `tool_calls`. Either way, an answer that turns out to be a call in some other shape (see
     * [LenientToolCalls]) is treated as the call it is -- which matters most on a native endpoint
     * whose model ignored the `tools` field and wrote the call out as text instead.
     */
    private fun interpretTurn(
        rawText: String,
        messageJson: JSONObject,
        syntax: AgenticSyntaxEntity?,
        toolNames: List<String>,
    ): ModelTurn {
        if (syntax != null) {
            val calls = SyntaxToolFormat.extractToolCalls(syntax, rawText)
                .ifEmpty { LenientToolCalls.extract(rawText, toolNames) }
            return if (calls.isNotEmpty()) ModelTurn.Calls(calls)
            else ModelTurn.Answer(LenientToolCalls.strip(SyntaxToolFormat.stripToolCallSpans(syntax, rawText), toolNames))
        }
        val turn = NativeToolFormat.parseMessage(messageJson)
        if (turn is ModelTurn.Answer) {
            val calls = LenientToolCalls.extract(turn.text, toolNames)
            if (calls.isNotEmpty()) return ModelTurn.Calls(calls)
        }
        return turn
    }

    private suspend fun resultMessage(tools: List<ToolDefinition>, call: ToolCall): AgenticMessage {
        val tool = tools.find { it.name == call.name }
        val result = if (tool == null) "Error: unknown tool '${call.name}'"
                     else AgenticToolExecutor.execute(tool, call.argumentsJson)
        return AgenticMessage("tool", result, toolCallId = call.callId, toolName = call.name)
    }

    private fun describeCalls(calls: List<ToolCall>): String =
        "Calling ${calls.joinToString(", ") { it.name }}..."

    /**
     * The standing instruction that makes offloading a behaviour rather than an option.
     *
     * Appended only when the user has enabled it. Without this a model is merely ABLE to call the
     * tools, and most will never think to until its context is already full -- which is exactly
     * the point at which the material it needed to save has been truncated away.
     */
    private fun contextInstruction(): String =
        if (!PrismSettings.getContextOffloadEnabled()) ""
        else "\n\n" + CONTEXT_INSTRUCTION

    /** The same bounded fold the plain backends use; see `AiManager.foldHistory`. */
    private fun foldHistory(userText: String, history: List<String>): String {
        val recent = history.filter { it.isNotBlank() }.takeLast(12)
        if (recent.isEmpty()) return userText
        return buildString {
            append("Conversation so far:\n")
            recent.forEach { line ->
                append(line.trim())
                append('\n')
            }
            append("\nNew message:\n")
            append(userText)
        }
    }
}
