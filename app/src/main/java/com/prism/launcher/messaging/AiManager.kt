package com.prism.launcher.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.P2pModelRegistry
import com.prism.launcher.vpn.PrismSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * Orchestrates AI interactions, deciding between Cloud and Local engines.
 */
object AiManager {

    /**
     * Checked once, right after a local text model is imported or activated for Sam (see the two
     * call sites: `ModelDownloadManager`, right after a fresh download completes; `ModelsPageView`,
     * when picking an already-imported model to activate). Never loads the model itself -- just
     * prepares Prism Swap settings so the NEXT load (when the user actually opens chat) already
     * has what it needs: "the swapfile size slider is raised according to the amount of RAM the
     * model needs," auto-enabling Prism Swap's checkbox in the process. [GgufInferenceService]'s
     * own three-tier retry ladder still runs at actual load time regardless -- this only means it
     * won't be starting from a swapfile too small to help.
     */
    fun onLocalTextModelActivated(context: Context, modelPath: String) {
        if (!GgufInferenceService.isGgufFile(modelPath)) return // MediaPipe .task models have their own separate RAM path (LocalAiService)
        val deficit = GgufInferenceService.ramDeficitBytes(modelPath) ?: return // fits already -- nothing to prepare

        val safetyMargin = 256L shl 20
        val neededSwapBytes = deficit + safetyMargin

        if (!PrismSettings.getPrismSwapEnabled()) {
            PrismSettings.setPrismSwapEnabled(true)
        }
        if (neededSwapBytes > PrismSettings.getPrismSwapBytes()) {
            PrismSettings.setPrismSwapBytes(neededSwapBytes)
        }
        com.prism.core.PrismPlatform.log.info(
            "AiManager",
            "$modelPath is short ~${"%.2f".format(deficit / (1024.0 * 1024.0 * 1024.0))}GB of free RAM -- " +
                "Prism Swap enabled, sized to at least ${"%.2f".format(neededSwapBytes / (1024.0 * 1024.0 * 1024.0))}GB"
        )
    }

    /**
     * @param onToken When non-null and streaming is enabled in Settings, invoked with each
     * incremental piece of text as the response is generated. Not called for non-text results
     * (image/video generation) or when streaming is disabled/unavailable — callers should always
     * rely on the returned [Pair] for the final text, and treat [onToken] as a purely additive
     * live-update signal.
     * @param onReasoning Local GGUF models only: invoked with a reasoning model's `<think>`
     * trace deltas, separate from [onToken], so callers can show it as a live "thinking"
     * indicator instead of dumping it into the answer.
     */
    /**
     * @param history the conversation so far, oldest first, EXCLUDING [userText]. Both speakers,
     *   in the order they spoke. Used only by CakeChat -- every other engine here is either
     *   stateless by design or keeps its own history, and handing them a second copy would
     *   duplicate what they already track.
     */
    /**
     * [allowOffload] is false when this device is ANSWERING a peer rather than asking one.
     *
     * WITHOUT IT, TWO PHONES POINTED AT EACH OTHER NEVER ANSWER. A asks B to run a prompt; B's own
     * market selection says "use A", so B asks A; A is already waiting on B, and both sockets sit
     * there until they time out. A device serving a request runs the model itself, always -- which
     * is what the requester asked for in the first place.
     *
     * A parameter and not a flag somewhere: this function hops threads through `withContext`, so
     * anything thread-local would be read on a different thread than it was set on and silently
     * fail to apply. Passing it makes the one caller that needs it say so.
     */
    /**
     * [plainText] is for callers that need a TEXT COMPLETION and nothing else.
     *
     * The default path is Sam's: it may notice a request for a picture and draw one, or hand the
     * whole turn to the agentic tool loop, or -- in Cloud mode -- route anything containing the
     * word "video" to a video generator. Every one of those is right for a chat window and wrong
     * for a caller that has composed a prompt and expects a string back.
     *
     * The language tutor is the caller this exists for. A lesson about films puts "video" in the
     * prompt; a learner whose interests include art makes an image request appear out of nowhere;
     * agentic tools turn a tutor into an agent with a shell. With this set, the only thing that
     * decides where the prompt goes is the user's Local / Cloud / Ollama choice -- so the feature
     * works identically on all three, which is the entire point.
     */
    /**
     * [conversationKey] names the conversation [history] belongs to ("sam", "character:<id>"). With
     * one, the WHOLE conversation is the model's context: stateless backends get all of it in every
     * request, and a local model either continues its own native context (when that context is
     * still exactly this conversation) or is cleared and given all of it again. Without one, this
     * behaves as before -- the callers that compose one-off prompts pass none.
     *
     * [speakers] says who said each line of [history] (true = the user), so the transcript the
     * model reads has names on it; [assistantName] is the name the other side goes by. [persona]
     * leads every rebuilt context (a character's framing). [recapCovers] is how many of the oldest
     * [history] lines the [recap] already stands for, so they are not sent twice.
     */
    suspend fun getResponse(
        context: Context,
        userText: String,
        imageUri: Uri? = null,
        onToken: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        history: List<String> = emptyList(),
        allowOffload: Boolean = true,
        plainText: Boolean = false,
        recap: String = "",
        conversationKey: String? = null,
        speakers: List<Boolean>? = null,
        assistantName: String = "Sam",
        persona: String = "",
        recapCovers: Int = 0,
    ): Pair<String, Pair<String?, String?>> = withContext(Dispatchers.IO) {
        val streaming = onToken != null && PrismSettings.getStreamingEnabled()
        val maxTokens = PrismSettings.getMaxTokens()
        val mode = PrismSettings.getAiMode()

        // A P2P model selection (Settings > AI Engine > P2P Models) always takes priority when
        // present — picking one is an explicit "use this" action, independent of the Local/Cloud/
        // Local Cloud mode toggle.
        val p2pModel = PrismSettings.getSelectedP2pModel()
        if (p2pModel != null) {
            // A peer's model is a text route like any other, so agentic tools reach it too: the
            // tools are described in the prompt and the calls are read back out of its reply.
            if (!plainText && PrismSettings.getAgenticToolsEnabled() && imageUri == null) {
                return@withContext com.prism.launcher.agentic.AgenticEngine.run(
                    userText, onToken, onReasoning, history,
                    complete = { prompt -> fetchP2pModelResponse(p2pModel.peerIp, prompt, null) },
                )
            }
            val textRaw = fetchP2pModelResponse(p2pModel.peerIp, userText, if (streaming) onToken else null)
            return@withContext Pair(textRaw, Pair(null, null))
        }

        // Agentic tool-calling (Settings > Agentic Tools) takes over the whole turn when enabled
        // -- it runs its own backend-specific request/response loop (see AgenticEngine), calling
        // real tools and re-invoking the model until it reaches a plain answer. Not supported
        // together with an image attachment in v1 (multimodal + tool-calling in one turn adds a
        // lot of per-backend complexity for a combination that's rarely needed together).
        if (!plainText && PrismSettings.getAgenticToolsEnabled() && imageUri == null) {
            return@withContext com.prism.launcher.agentic.AgenticEngine.run(
                userText, onToken, onReasoning, history,
            )
        }

        // Image generation, for every AI mode rather than Cloud only.
        //
        // This used to sit inside the AI_MODE_CLOUD branch below, which meant a user who had
        // imported an on-device image model had no way to reach it through Sam at all -- asking
        // her to draw something in Local mode just fell through to the text model, which would
        // describe a picture instead of making one. ImageGenManager already picks the right engine
        // per mode, so the trigger belongs above the mode split, not inside one arm of it.
        //
        // Gated on hasImageGenerator() so that with no generator loaded this falls through to a
        // normal text reply, rather than returning a broken "here's your image" with nothing
        // attached.
        if (!plainText && PrismSettings.hasImageGenerator()) {
            val imagePrompt = ImageGenManager.parseImageRequest(userText)
            if (imagePrompt != null) {
                val uri = ImageGenManager.generateImage(context, imagePrompt)
                return@withContext if (uri != null) {
                    Pair("Here's what I made for \"$imagePrompt\".", Pair(uri.toString(), "image"))
                } else {
                    // Never claim success on a null Uri: the old code returned "I've generated a
                    // high-fidelity image for you." regardless, so a failed generation looked like
                    // a delivered image that simply didn't render.
                    Pair(
                        "I couldn't generate that image — the loaded image model may not be working. " +
                            "Check Settings > AI Engine.",
                        Pair(null, null)
                    )
                }
            }
        }

        // THE HISTORY IS FOLDED INTO THE PROMPT for the stateless backends. It used to be passed
        // only to CakeChat, so Cloud Sam answered every message as if the conversation had just
        // started -- the transcript was on screen, but none of it reached the model. Local GGUF is
        // deliberately excluded below: llama.cpp keeps its own context for the life of the loaded
        // model, and sending the turns again would duplicate every one of them.
        val recapText = recap.trim()
        // The lines the recap already stands for are not sent again: that is the whole point of
        // having compacted them.
        val skip = if (recapText.isEmpty()) 0 else recapCovers.coerceIn(0, history.size)
        val liveHistory = history.drop(skip)
        val liveSpeakers = speakers?.takeIf { it.size == history.size }?.drop(skip)
        val prompt = foldHistory(
            userText, liveHistory, recapText,
            speakers = liveSpeakers,
            assistantName = assistantName,
            persona = persona,
            whole = conversationKey != null,
        )

        if (mode == PrismSettings.AI_MODE_CLOUD) {
            // A REAL REQUEST, WHATEVER THE PROMPT SAYS. The old branch sent anything containing
            // "video" to a hardcoded mock that answered with a sample MP4 from w3schools, presented
            // as model output. CloudSam now always asks the configured model; if it cannot answer,
            // the provider's own error comes back and is shown as a failure rather than theatre.
            val cloudModel = PrismSettings.getActiveCloudModel()
                ?: return@withContext Pair("Error: No cloud model selected. Add one in Settings > AI Engine > Manage Cloud Models.", Pair(null, null))

            val base64Image = imageUri?.let { encodeImageToBase64(context, it) }

            val textRaw = if (streaming) {
                CloudAiService.fetchResponseStreaming(cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId, prompt, base64Image, maxTokens, onToken!!)
            } else {
                CloudAiService.fetchResponse(cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId, prompt, base64Image)
            }
            return@withContext Pair(textRaw, Pair(null, null))
        } else if (mode == PrismSettings.AI_MODE_LOCAL_CLOUD) {
            val endpoint = PrismSettings.getSelectedOllamaEndpoint()
                ?: return@withContext Pair("No Ollama model selected — scan for servers and pick one in Settings > AI Engine.", Pair(null, null))

            val textRaw = if (streaming) {
                CloudAiService.fetchOllamaChatStreaming(endpoint.host, endpoint.port, endpoint.model, prompt, onToken!!)
            } else {
                CloudAiService.fetchOllamaChat(endpoint.host, endpoint.port, endpoint.model, prompt)
            }
            return@withContext Pair(textRaw, Pair(null, null))
        } else {
            // A trained CakeChat answers here when the user has chosen it. Checked BEFORE the
            // model-path guard, because CakeChat has no .gguf -- its weights are Keras .h5 driven
            // by its own Python -- so requiring a local model path first would make it
            // unreachable no matter how well it had trained.
            val cakeChatChosen = PrismSettings.getUseCakeChat()
            var cakeChatProblem: String? = null

            if (cakeChatChosen) {
                if (com.prism.launcher.cakechat.CakeChatEngine.isReady(context)) {
                    // FREED BEFORE CAKECHAT LOADS. llama.cpp holds its weights resident in this
                    // process, and CakeChat's TensorFlow allocates hundreds of megabytes more in
                    // its own. They are separate processes but they draw on the same device
                    // memory, and Android's low-memory killer counts the total -- so leaving a
                    // GGUF model loaded that the user has chosen not to use is what pushes the
                    // CakeChat process over the edge and gets it killed mid-load.
                    //
                    // Costs a reload if CakeChat then fails and this falls through, which is the
                    // right trade: that path is the exception, and it reloads by itself.
                    val ggufPath = PrismSettings.getLocalAiModelPath()
                    if (ggufPath.isNotBlank()) {
                        runCatching { GgufInferenceService.unload(ggufPath) }
                    }

                    // THE WHOLE CONVERSATION, not just this message. CakeChat keeps no state
                    // between calls -- its encoder takes the recent turns as an input on every
                    // call, and anything not passed simply did not happen as far as the model is
                    // concerned. It reads the last few and pads the rest, so handing it everything
                    // costs nothing: the input tensor is a fixed size either way.
                    val reply = com.prism.launcher.cakechat.CakeChatEngine.respond(
                        context.applicationContext, history + userText
                    )
                    if (reply != null) {
                        onToken?.invoke(reply)
                        return@withContext Pair(reply, Pair(null, null))
                    }
                    // Falls through to the GGUF engine rather than failing: a CakeChat that cannot
                    // answer is a reason to use the other local model, not a reason to say nothing.
                    cakeChatProblem = "CakeChat did not return a reply — see System Diagnostics."
                    com.prism.launcher.PrismLogger.logWarning("CakeChat", "No reply; falling back to the local GGUF model")
                } else {
                    cakeChatProblem =
                        "CakeChat is selected but not ready — it needs to be installed and trained, " +
                            "or a trained model imported, on this device."
                    com.prism.launcher.PrismLogger.logWarning("CakeChat", "Selected but not ready; falling back")
                }
            }

            val modelPath = PrismSettings.getLocalAiModelPath()
            if (modelPath.isBlank()) {
                // NAMES THE ACTUAL PROBLEM. Telling someone who deliberately selected CakeChat to
                // "select a local model" describes a choice they already made, and sends them to
                // Settings to fix something that is not wrong.
                val message = cakeChatProblem
                    ?: "Error: No local model selected. Please download or select one in Settings."
                return@withContext Pair(message, Pair(null, null))
            }

            // The compute market, if the user pointed it at someone else's hardware.
            //
            // Checked here rather than earlier because everything above is about WHICH model to
            // run; this is about WHERE. And it is deliberately last: a failure to reach the mesh
            // falls through to running locally, so a peer going to sleep costs speed, not an answer.
            //
            // WHEN A RECAP EXISTS, THE ENGINE'S OWN MEMORY IS NO LONGER THE CONVERSATION. The older
            // turns were folded into that recap and llama.cpp's context was cleared at that moment
            // (see SamCompaction and ConversationActivity.compactSam), so what it still holds is a
            // fragment beginning mid-thread. From then on the recap and the recent turns are resent
            // the way the stateless backends already do: a little more prompt per turn, in exchange
            // for a model that still knows what the conversation was about.
            //
            // A KEYED CONVERSATION IS ALWAYS WHOLE. llama.cpp holds ONE conversation, shared by Sam,
            // every character and every one-off caller, and it starts empty whenever the model is
            // (re)loaded -- so "llama.cpp keeps its own context" was true only until a character
            // spoke, the app restarted or the idle unloader ran, after which the model answered
            // with no memory of the thread on screen. Now: if the native context is still exactly
            // this conversation (same owner, same number of turns, same recap, and nothing else has
            // generated or reset since -- see GgufInferenceService.sessionEpoch), only the new
            // message is sent and the model continues where it was. Otherwise it is cleared and
            // given the entire conversation again.
            val localText = when {
                conversationKey != null -> {
                    val session = localSession
                    val inSync = session != null &&
                        session.key == conversationKey &&
                        session.turns == liveHistory.size &&
                        session.recap == recapText &&
                        session.epoch == GgufInferenceService.sessionEpoch
                    if (inSync) {
                        userText
                    } else {
                        runCatching { GgufInferenceService.resetConversation() }
                        prompt
                    }
                }
                recapText.isEmpty() -> userText
                else -> {
                    runCatching { GgufInferenceService.resetConversation() }
                    prompt
                }
            }

            if (allowOffload) {
                // A peer has none of this conversation, so it always gets all of it.
                meshResponse(context, modelPath, if (conversationKey != null) prompt else localText, onToken)?.let {
                    return@withContext Pair(it, Pair(null, null))
                }
            }

            val response = if (streaming) {
                LocalAiService.generateResponseStreaming(context, modelPath, localText, maxTokens, onToken!!, onReasoning)
            } else {
                LocalAiService.generateResponse(context, modelPath, localText)
            }
            // Recorded AFTER the turn, with the epoch this turn's own generation produced: the next
            // turn is in sync only if nothing else touched the context in between. A failed turn
            // leaves the context in an unknown state, so the next one starts clean.
            val failed = response.trim().let {
                it.startsWith("Error") || it.startsWith("Local AI Error") || it.startsWith("Context window full")
            }
            localSession = if (conversationKey != null && !failed) {
                LocalSession(conversationKey, liveHistory.size + 2, recapText, GgufInferenceService.sessionEpoch)
            } else null
            return@withContext Pair(response, Pair(null, null))
        }
    }

    /**
     * Runs this generation on the mesh, or returns null to run it here.
     *
     * ## Two shapes, and why the split is where it is
     *
     * ONE peer: the prompt goes to that peer and it runs its own model. Nothing is split, no
     * weights move, and it works even when this device could not load the model at all.
     *
     * SEVERAL peers: this device keeps the model file and llama.cpp spreads its layers across the
     * peers over ggml's RPC backend -- the case a phone that is short of RAM actually needs, since
     * the memory the model does not fit in is someone else's. The weights are uploaded once at load
     * time, which is why this is never done implicitly: the user has to have picked more than one
     * device.
     *
     * Returns null on every failure rather than an error string. The caller then runs locally, and
     * a sleeping peer costs a slower answer instead of no answer.
     */
    private fun meshResponse(
        context: Context,
        modelPath: String,
        userText: String,
        onToken: ((String) -> Unit)?,
    ): String? {
        // The payment gate, not just the availability one. A generation on somebody else's phone is
        // billed, and if the wallet cannot cover it the user has to have agreed to pay later --
        // which is what turns on the mining that funds it. Blocked means this runs locally, which is
        // the same fallback every other failure here takes.
        val verdict = com.prism.launcher.mesh.MeshInference.offloadVerdict()
        if (verdict is com.prism.launcher.mesh.PayLaterPolicy.Verdict.Blocked) {
            com.prism.launcher.PrismLogger.logInfo("MeshCompute", "Staying local: ${verdict.reason}")
            return null
        }
        val peers = com.prism.launcher.mesh.MeshInference.selectedPeers()

        if (peers.size == 1) {
            return com.prism.launcher.mesh.MeshInference.runOnPeer(peers.first(), userText, onToken)
        }

        val handle = com.prism.launcher.mesh.MeshInference.loadDistributed(
            // 2048 is what every local load uses (see GgufInferenceService.ensureLoaded); the
            // context window is a property of the model and the device's memory, not of where the
            // layers happen to live.
            modelPath, 2048
        )
        if (handle == 0L) return null

        return try {
            val answer = GgufInferenceService.generateWithHandle(handle, userText, onToken)
            com.prism.launcher.mesh.MeshInference.billDistributed(peers, answer.length)
            answer.ifBlank { null }
        } finally {
            // Freed after every distributed generation rather than kept warm. The peers' buffers
            // are their memory, not ours, and holding a split model open would keep several other
            // people's phones pinned for a conversation that may be over.
            GgufInferenceService.freeHandle(handle)
        }
    }

    /**
     * Sends a prompt to a peer hosting a model over the Prism mesh (see [P2pModelRegistry] /
     * [com.prism.launcher.vpn.PrismAiHost]) and streams the response back. Reuses the exact same
     * transport as P2P website hosting — [PrismSocket]'s PRISM_CONNECT handshake into port 8080 —
     * just with the reserved [P2pModelRegistry.MODEL_HOST_DOMAIN] marker instead of a real
     * hosted-site domain, and a bespoke `POST /generate` instead of a GET for a file.
     */
    /** How long a peer may go silent mid-answer before its inference is abandoned. */
    private const val P2P_INFERENCE_READ_TIMEOUT_MS = 300_000

    /**
     * The mesh-peer inference path, for callers outside this file.
     *
     * Exposed so the compute market can route a generation to a peer the user picked, rather than
     * only to whichever peer happens to be hosting a model. Returns null on failure instead of the
     * error string [fetchP2pModelResponse] hands the chat path, because the market's caller falls
     * back to running locally and must be able to tell an answer from an apology.
     */
    fun generateOnPeer(peerIp: String, prompt: String, onToken: ((String) -> Unit)?): String? {
        val response = fetchP2pModelResponse(peerIp, prompt, onToken)
        return if (response.startsWith("P2P Model Error:") || response.startsWith("Error:")) null else response
    }

    private fun fetchP2pModelResponse(peerIp: String, userText: String, onToken: ((String) -> Unit)?): String {
        var socket: PrismSocket? = null
        return try {
            socket = PrismSocket()
            socket.setHostHint(P2pModelRegistry.MODEL_HOST_DOMAIN)
            socket.connect(InetSocketAddress(peerIp, 8080), 10000)
            // Set AFTER connect: the PRISM_CONNECT handshake inside connect() uses its own much
            // shorter timeout, and used to leave it on the socket -- which is why asking a peer to
            // run a mesh-hosted model timed out. Five seconds is not a generation, it is barely a
            // model load.
            //
            // Applies per read, so streaming keeps the connection alive indefinitely as long as
            // tokens keep coming; what it bounds is the FIRST one, where the peer may still be
            // pulling gigabytes of weights off storage before it can start. Bounded rather than
            // infinite so a peer that dies mid-answer fails the request instead of pinning this
            // thread forever.
            socket.soTimeout = P2P_INFERENCE_READ_TIMEOUT_MS

            val bodyBytes = userText.toByteArray()
            val request = "POST /generate HTTP/1.1\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            val output = socket.getOutputStream()
            output.write(request.toByteArray())
            output.write(bodyBytes)
            output.flush()

            val input = socket.getInputStream()
            skipHttpHeaders(input)

            val accumulator = StringBuilder()
            val buffer = ByteArray(2048)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                val piece = String(buffer, 0, n)
                accumulator.append(piece)
                onToken?.invoke(piece)
            }

            if (accumulator.isEmpty()) "Error: No response from peer." else accumulator.toString()
        } catch (e: Exception) {
            val error = "P2P Model Error: ${e.message}"
            onToken?.invoke(error)
            error
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun skipHttpHeaders(input: java.io.InputStream) {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") break
        }
    }

    /** Which conversation the local model's native context currently holds; see the local path. */
    private data class LocalSession(val key: String, val turns: Int, val recap: String, val epoch: Long)

    @Volatile
    private var localSession: LocalSession? = null

    /**
     * The conversation followed by the new message, for a model that has to be given all of it.
     *
     * With [whole] (a keyed conversation) EVERY line is included: the user asked for the entire
     * history to be the context, and the length is managed by compaction -- the context pill, and
     * the automatic compaction before a turn that would not fit -- rather than by silently dropping
     * the oldest lines here. Without it, the old bound of twelve lines applies, for callers that
     * were never keyed. Lines are named by speaker when [speakers] is given, so the model knows
     * which of them it said.
     */
    private fun foldHistory(
        userText: String,
        history: List<String>,
        recap: String = "",
        speakers: List<Boolean>? = null,
        assistantName: String = "Sam",
        persona: String = "",
        whole: Boolean = false,
    ): String {
        val named = history.mapIndexed { index, line ->
            val who = speakers?.getOrNull(index)
            when (who) {
                true -> "User: ${line.trim()}"
                false -> "$assistantName: ${line.trim()}"
                null -> line.trim()
            }
        }.filter { it.isNotBlank() }
        val recent = if (whole) named else named.takeLast(12)
        val briefing = SamCompaction.preamble(recap)
        val framing = persona.trim()
        if (recent.isEmpty() && briefing.isEmpty() && framing.isEmpty()) return userText
        return buildString {
            // Who the model is comes before what has been said, and the recap before the recent
            // turns: it stands for everything older, and the past has to be met before the present.
            if (framing.isNotEmpty()) {
                append(framing)
                append("\n\n")
            }
            if (briefing.isNotEmpty()) {
                append(briefing)
                append("\n\n")
            }
            if (recent.isNotEmpty()) {
                append("Conversation so far:\n")
                recent.forEach { line ->
                    append(line)
                    append('\n')
                }
                append('\n')
            }
            // No trailing "$assistantName:" cue: every backend here wraps this in a chat template as
            // the user's turn, and the model answers it as itself without being prompted to.
            append(if (speakers != null) "User: " else "New message:\n")
            append(userText)
        }
    }

    private fun encodeImageToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
