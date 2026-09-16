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
    suspend fun getResponse(context: Context, userText: String, imageUri: Uri? = null, onToken: ((String) -> Unit)? = null, onReasoning: ((String) -> Unit)? = null, history: List<String> = emptyList(), allowOffload: Boolean = true): Pair<String, Pair<String?, String?>> = withContext(Dispatchers.IO) {
        val streaming = onToken != null && PrismSettings.getStreamingEnabled()
        val maxTokens = PrismSettings.getMaxTokens()
        val mode = PrismSettings.getAiMode()

        // A P2P model selection (Settings > AI Engine > P2P Models) always takes priority when
        // present — picking one is an explicit "use this" action, independent of the Local/Cloud/
        // Local Cloud mode toggle.
        val p2pModel = PrismSettings.getSelectedP2pModel()
        if (p2pModel != null) {
            val textRaw = fetchP2pModelResponse(p2pModel.peerIp, userText, if (streaming) onToken else null)
            return@withContext Pair(textRaw, Pair(null, null))
        }

        // Agentic tool-calling (Settings > Agentic Tools) takes over the whole turn when enabled
        // -- it runs its own backend-specific request/response loop (see AgenticEngine), calling
        // real tools and re-invoking the model until it reaches a plain answer. Not supported
        // together with an image attachment in v1 (multimodal + tool-calling in one turn adds a
        // lot of per-backend complexity for a combination that's rarely needed together).
        if (PrismSettings.getAgenticToolsEnabled() && imageUri == null) {
            return@withContext com.prism.launcher.agentic.AgenticEngine.run(userText, onToken, onReasoning)
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
        if (PrismSettings.hasImageGenerator()) {
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

        if (mode == PrismSettings.AI_MODE_CLOUD) {
            if (userText.contains("video", ignoreCase = true)) {
                return@withContext CloudAiService.generateResponse(userText)
            }

            // Priority 2: Real Cloud API call
            val cloudModel = PrismSettings.getActiveCloudModel()
                ?: return@withContext Pair("Error: No cloud model selected. Add one in Settings > AI Engine > Manage Cloud Models.", Pair(null, null))

            val base64Image = imageUri?.let { encodeImageToBase64(context, it) }

            val textRaw = if (streaming) {
                CloudAiService.fetchResponseStreaming(cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId, userText, base64Image, maxTokens, onToken!!)
            } else {
                CloudAiService.fetchResponse(cloudModel.baseUrl, cloudModel.apiKey, cloudModel.modelId, userText, base64Image)
            }
            return@withContext Pair(textRaw, Pair(null, null))
        } else if (mode == PrismSettings.AI_MODE_LOCAL_CLOUD) {
            val endpoint = PrismSettings.getSelectedOllamaEndpoint()
                ?: return@withContext Pair("No Ollama model selected — scan for servers and pick one in Settings > AI Engine.", Pair(null, null))

            val textRaw = if (streaming) {
                CloudAiService.fetchOllamaChatStreaming(endpoint.host, endpoint.port, endpoint.model, userText, onToken!!)
            } else {
                CloudAiService.fetchOllamaChat(endpoint.host, endpoint.port, endpoint.model, userText)
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
            if (allowOffload) {
                meshResponse(context, modelPath, userText, onToken)?.let {
                    return@withContext Pair(it, Pair(null, null))
                }
            }

            val response = if (streaming) {
                LocalAiService.generateResponseStreaming(context, modelPath, userText, maxTokens, onToken!!, onReasoning)
            } else {
                LocalAiService.generateResponse(context, modelPath, userText)
            }
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
        if (!com.prism.launcher.mesh.MeshInference.shouldOffload()) return null
        val peers = com.prism.launcher.mesh.MeshInference.selectedPeers()

        if (peers.size == 1) {
            return com.prism.launcher.mesh.MeshInference.runOnPeer(context, peers.first(), userText, onToken)
        }

        val handle = com.prism.launcher.mesh.MeshInference.loadDistributed(
            // 2048 is what every local load uses (see GgufInferenceService.ensureLoaded); the
            // context window is a property of the model and the device's memory, not of where the
            // layers happen to live.
            context, modelPath, 2048
        )
        if (handle == 0L) return null

        return try {
            val answer = GgufInferenceService.generateWithHandle(handle, userText, onToken)
            com.prism.launcher.mesh.MeshInference.billDistributed(context, peers, answer.length)
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
