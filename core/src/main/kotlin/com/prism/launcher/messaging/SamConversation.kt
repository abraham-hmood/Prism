package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.Base64

/**
 * A conversation with Sam, without an Activity.
 *
 * ## Why this exists rather than porting `AiManager`
 *
 * `AiManager.getResponse` is the Android conversation path and it cannot move: it takes a `Context` and
 * a `Uri`, reaches into MediaStore to save generated images, and routes a turn into the agentic tool
 * loop. Most of that is genuinely Android. What is NOT Android is the part a conversation actually needs
 * — pick a backend, stream tokens, persist both sides, attach an image — and every piece of that was
 * already in :core: [CloudAiService] for the hosted path, [GgufInferenceService] for the local one,
 * [AiMessageDao] for the transcript.
 *
 * So this is the portable middle: the turn logic, with the platform-specific choices left to the caller.
 * Android keeps `AiManager` and loses nothing; desktop gets a conversation that behaves the same way.
 *
 * ## What is deliberately not here
 *
 * **Nora.** Her transcript is a flat JSON file rather than a Room table and her generation runs on the
 * CPU through her own service, so she is a different conversation with a different store — which is
 * exactly how the Android build treats her, on a separate thread id. Desktop already has `NoraChatPage`.
 * Merging the two would mean one of them pretending to be the other.
 *
 * **Image generation and the agentic loop.** Both are turn-stealing behaviours that belong to the chat
 * surface's own policy: `AiManager` decides that "draw me a cat" is a generation rather than a reply,
 * and that decision is different in a language tutor than in a chat window. [ImageGeneration] is
 * reachable directly by any caller that wants it.
 *
 * ## Why streaming is a callback and not a Flow
 *
 * Because the two backends stream differently and neither is suspending: the cloud path reads an
 * HTTP chunked body on the calling thread and llama.cpp calls back from native code. Wrapping either in
 * a Flow would mean a channel and a coroutine per turn to deliver tokens a callback already delivers.
 * Callers put this on a background thread and post to their own UI.
 */
object SamConversation {

    private const val TAG = "PrismSam"

    /** Which backend answered, so a UI can say. */
    enum class Backend(val label: String) {
        CLOUD("Cloud"),
        /** An Ollama server on the local network. Prism calls this mode "Local Cloud". */
        OLLAMA("Ollama"),
        LOCAL("On-device"),
        /** A trained CakeChat. Needs a platform responder -- see [cakeChatResponder]. */
        CAKECHAT("CakeChat"),
        /** Another device's model, over the meshnet. The weights never move -- see [MeshModels]. */
        MESH("On another device"),
        NONE("nothing configured"),
    }

    /**
     * How this platform gets a reply out of CakeChat, if it can.
     *
     * A HOOK RATHER THAN A CALL because CakeChat is the one backend whose inference is genuinely
     * platform-specific. Android embeds Python through Chaquopy and calls it in-process; desktop drives
     * a real interpreter as a subprocess, and at the time of writing its desktop implementation can
     * install and train but NOT generate. So the platform installs a responder if it has one, and
     * [backend] reports CakeChat as unavailable if it does not — which matters, because a user who
     * activated CakeChat and silently got the GGUF model instead would have no way to know.
     *
     * Takes the prompt and the conversation so far. CakeChat is a seq2seq dialogue model and is the one
     * backend here that genuinely wants the history as turns rather than folded into a prompt.
     */
    @Volatile
    var cakeChatResponder: ((String, List<String>) -> String?)? = null

    data class Turn(
        val text: String,
        val backend: Backend,
        val millis: Long,
        val error: String? = null,
        /** The token this answer can be rated against. See [NoraFeedbackBridge]. */
        val feedbackToken: String? = null,
    ) {
        val ok: Boolean get() = error == null && text.isNotBlank()
    }

    /**
     * Which backend a turn will use, and why not if it will not.
     *
     * Checked before a send rather than discovered during one: a chat window that accepts a message and
     * then reports there is no model has already put the user's text in the transcript.
     */
    fun backend(): Backend = when {
        // A MESH MODEL WINS OVER EVERYTHING, because choosing one is an explicit act aimed at a
        // specific model on a specific machine. Falling through to a local model because the peer was
        // briefly unreachable would answer in a different voice from a different model and say nothing
        // about it, which is worse than reporting that the machine is not answering.
        PrismSettings.getSelectedP2pModel() != null -> Backend.MESH

        // CAKECHAT FIRST, and before the model-path guard, because CakeChat has no .gguf -- its weights
        // are Keras and a user who activated it has no local model path set. This is the order the
        // Android build checks in, for the same reason.
        PrismSettings.getUseCakeChat() && cakeChatResponder != null -> Backend.CAKECHAT

        PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL_CLOUD &&
            PrismSettings.getSelectedOllamaEndpoint() != null -> Backend.OLLAMA

        PrismSettings.getAiMode() == PrismSettings.AI_MODE_CLOUD &&
            PrismSettings.getActiveCloudModel() != null -> Backend.CLOUD

        PrismSettings.getLocalAiModelPath().isNotBlank() -> Backend.LOCAL

        // Fall back to whatever is configured rather than refusing because the MODE does not match.
        // Somebody with a cloud key and no local model should get an answer.
        PrismSettings.getActiveCloudModel() != null -> Backend.CLOUD
        PrismSettings.getSelectedOllamaEndpoint() != null -> Backend.OLLAMA
        else -> Backend.NONE
    }

    fun unavailableReason(): String = when {
        // The precise case, checked before the generic one: the user DID choose a backend and this
        // platform cannot run it. Saying "no model configured" here would be wrong and confusing.
        PrismSettings.getUseCakeChat() && cakeChatResponder == null ->
            "CakeChat is selected but this build cannot generate with it -- the desktop implementation " +
                "can install and train CakeChat but not run inference yet. Pick another model on the " +
                "Models page, or turn CakeChat off."

        backend() == Backend.NONE ->
            "No model to answer with. Import a .gguf on the Models page, configure a cloud model on " +
                "the Cloud AI page, or pick an Ollama server on your network."

        else -> ""
    }

    /**
     * Runs one turn.
     *
     * Blocking. [onToken] is called with each delta when streaming is enabled and the backend supports
     * it; the whole answer is returned regardless, so a caller that ignores tokens still works.
     *
     * [onReasoning] receives a reasoning model's `<think>` trace, separately from the answer. That
     * separation is what a thinking indicator needs: the trace is shown while it arrives and is NOT part
     * of the reply. Local models only -- a hosted endpoint does not expose it.
     *
     * [attachment] is sent to a vision-capable cloud model as an inline image. The LOCAL path ignores
     * it and says so in the returned turn rather than silently dropping it: llama.cpp in this build has
     * no multimodal projector wired up, and an attachment that vanished without comment would look like
     * the model choosing not to mention the picture.
     */
    fun send(
        userText: String,
        attachment: File? = null,
        history: List<String> = emptyList(),
        onToken: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        standalone: Boolean = false,
    ): Turn {
        val started = System.currentTimeMillis()
        if (userText.isBlank() && attachment == null) {
            return Turn("", Backend.NONE, 0, "Nothing to send.")
        }

        // A turn that is not part of a conversation. The local engine holds conversation state natively
        // and appends every call to it, so a caller generating independent things has to say so or the
        // previous prompt is still in the context -- see GgufInferenceService.resetConversation. The other
        // backends take their history as an argument and are already stateless, so this is a no-op there.
        if (standalone) runCatching { GgufInferenceService.resetConversation() }

        return when (val target = backend()) {
            Backend.NONE -> Turn("", target, 0, unavailableReason())
            Backend.CLOUD -> cloudTurn(userText, attachment, history, onToken, started)
            Backend.LOCAL -> localTurn(userText, attachment, history, onToken, onReasoning, started)
            Backend.OLLAMA -> ollamaTurn(userText, history, onToken, started)
            Backend.CAKECHAT -> cakeChatTurn(userText, history, started)
            Backend.MESH -> meshTurn(userText, history, started)
        }
    }

    /**
     * Runs the turn on another device's model.
     *
     * The prompt carries its own history, because the far side holds no conversation for this client --
     * it is answering requests, not keeping a chat. Same reason the local path builds a prompt rather
     * than relying on the engine's memory.
     */
    private fun meshTurn(userText: String, history: List<String>, started: Long): Turn {
        val selected = PrismSettings.getSelectedP2pModel()
            ?: return Turn("", Backend.MESH, 0, "No mesh model is selected.")

        val host = MeshModels.Hosted(selected.peerIp, selected.modelName)
        val answer = MeshModels.generate(host, withHistory(userText, history))
            ?: return Turn(
                "", Backend.MESH, System.currentTimeMillis() - started,
                selected.modelName + " on " + selected.peerIp + " did not answer. That device may be " +
                    "off, on another network, or no longer sharing it.",
            )

        return Turn(
            text = answer.trim(),
            backend = Backend.MESH,
            millis = System.currentTimeMillis() - started,
            error = if (answer.isBlank()) "The peer returned nothing." else null,
        )
    }

    private fun cloudTurn(
        userText: String,
        attachment: File?,
        history: List<String>,
        onToken: ((String) -> Unit)?,
        started: Long,
    ): Turn {
        val model = PrismSettings.getActiveCloudModel()
            ?: return Turn("", Backend.CLOUD, 0, "No cloud model is configured.")

        val base64 = attachment?.takeIf { it.isFile && Vision.isSupported(it) }?.let { file ->
            runCatching { Base64.getEncoder().encodeToString(file.readBytes()) }.getOrNull()
        }
        val prompt = withHistory(userText, history)

        return runCatching {
            val answer = if (onToken != null && PrismSettings.getStreamingEnabled()) {
                CloudAiService.fetchResponseStreaming(
                    model.baseUrl, model.apiKey, model.modelId, prompt, base64,
                    PrismSettings.getMaxTokens(), onToken,
                )
            } else {
                CloudAiService.fetchResponse(model.baseUrl, model.apiKey, model.modelId, prompt, base64)
            }
            // The service returns its failures as prose, which is what a successful answer also is. The
            // prefix is the only thing distinguishing them, so it is checked rather than assumed away.
            if (answer.startsWith("Cloud AI Error", ignoreCase = true)) {
                Turn("", Backend.CLOUD, System.currentTimeMillis() - started, answer)
            } else {
                Turn(answer.trim(), Backend.CLOUD, System.currentTimeMillis() - started)
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Cloud turn failed", it)
            Turn("", Backend.CLOUD, System.currentTimeMillis() - started,
                it.message ?: it::class.simpleName.orEmpty())
        }
    }

    /**
     * An Ollama server on the local network.
     *
     * Fully portable and it always was -- [CloudAiService.fetchOllamaChat] and the endpoint setting are
     * both in :core. It was missing from this router purely because the first version only knew about
     * cloud and local, which meant a user in Local Cloud mode silently got a different backend than the
     * one they chose.
     *
     * No attachment: the Ollama chat endpoint here posts text only. Reported rather than dropped.
     */
    private fun ollamaTurn(
        userText: String,
        history: List<String>,
        onToken: ((String) -> Unit)?,
        started: Long,
    ): Turn {
        val endpoint = PrismSettings.getSelectedOllamaEndpoint()
            ?: return Turn("", Backend.OLLAMA, 0, "No Ollama server is selected.")
        val prompt = withHistory(userText, history)

        return runCatching {
            val answer = if (onToken != null && PrismSettings.getStreamingEnabled()) {
                CloudAiService.fetchOllamaChatStreaming(
                    endpoint.host, endpoint.port, endpoint.model, prompt, onToken,
                )
            } else {
                CloudAiService.fetchOllamaChat(endpoint.host, endpoint.port, endpoint.model, prompt)
            }
            Turn(answer.trim(), Backend.OLLAMA, System.currentTimeMillis() - started)
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Ollama turn failed", it)
            Turn("", Backend.OLLAMA, System.currentTimeMillis() - started,
                it.message ?: it::class.simpleName.orEmpty())
        }
    }

    /**
     * A trained CakeChat, through whatever the platform installed.
     *
     * Never streams: CakeChat generates a whole reply from its decoder and has no token callback to
     * expose. A caller that shows a streaming cursor will simply see the answer arrive at once, which is
     * the truth about this backend rather than a fake drip.
     */
    private fun cakeChatTurn(userText: String, history: List<String>, started: Long): Turn {
        val responder = cakeChatResponder
            ?: return Turn("", Backend.CAKECHAT, 0, unavailableReason())
        return runCatching {
            // The history goes as TURNS, not folded into the prompt: CakeChat is a seq2seq dialogue
            // model and its context is the exchange, not a block of text.
            val answer = responder(userText, history)
            if (answer.isNullOrBlank()) {
                Turn("", Backend.CAKECHAT, System.currentTimeMillis() - started,
                    "CakeChat produced nothing.")
            } else {
                Turn(answer.trim(), Backend.CAKECHAT, System.currentTimeMillis() - started)
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "CakeChat turn failed", it)
            Turn("", Backend.CAKECHAT, System.currentTimeMillis() - started,
                it.message ?: it::class.simpleName.orEmpty())
        }
    }

    private fun localTurn(
        userText: String,
        attachment: File?,
        history: List<String>,
        onToken: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?,
        started: Long,
    ): Turn {
        val path = PrismSettings.getLocalAiModelPath()
        if (path.isBlank()) return Turn("", Backend.LOCAL, 0, "No local model is active.")

        val prompt = withHistory(userText, history)
        return runCatching {
            val answer = if (onToken != null && PrismSettings.getStreamingEnabled()) {
                // onReasoning is what makes the thinking indicator real rather than decorative. The
                // service already splits a reasoning model's <think> trace from its answer, so the two
                // arrive on separate callbacks -- without passing it, the trace would be concatenated
                // into the reply and shown to the user as part of the answer.
                GgufInferenceService.generateResponseStreaming(
                    path, prompt, PrismSettings.getMaxTokens(), onToken, onReasoning,
                )
            } else {
                GgufInferenceService.generateResponse(path, prompt)
            }
            Turn(
                text = answer.trim(),
                backend = Backend.LOCAL,
                millis = System.currentTimeMillis() - started,
                error = attachment?.let {
                    // Reported, not swallowed. See the note on [send].
                    "The on-device model cannot see images; ${it.name} was not sent."
                }.takeIf { answer.isBlank() },
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Local turn failed", it)
            Turn("", Backend.LOCAL, System.currentTimeMillis() - started,
                it.message ?: it::class.simpleName.orEmpty())
        }
    }

    /**
     * Folds recent turns into the prompt.
     *
     * Both backends here are stateless per call, so context has to be resent. Bounded at
     * [HISTORY_TURNS] because it is resent EVERY turn: an unbounded transcript grows the prompt without
     * limit until the model's context window truncates it silently from the front, which drops the
     * system framing first and the current question last.
     */
    private fun withHistory(userText: String, history: List<String>): String {
        if (history.isEmpty()) return userText
        val recent = history.takeLast(HISTORY_TURNS)
        return buildString {
            recent.forEach { line ->
                append(line.trim())
                append('\n')
            }
            append(userText)
        }
    }

    private const val HISTORY_TURNS = 10
}
