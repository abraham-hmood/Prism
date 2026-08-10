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
     * @param onToken When non-null and streaming is enabled in Settings, invoked with each
     * incremental piece of text as the response is generated. Not called for non-text results
     * (image/video generation) or when streaming is disabled/unavailable — callers should always
     * rely on the returned [Pair] for the final text, and treat [onToken] as a purely additive
     * live-update signal.
     * @param onReasoning Local GGUF models only: invoked with a reasoning model's `<think>`
     * trace deltas, separate from [onToken], so callers can show it as a live "thinking"
     * indicator instead of dumping it into the answer.
     */
    suspend fun getResponse(context: Context, userText: String, imageUri: Uri? = null, onToken: ((String) -> Unit)? = null, onReasoning: ((String) -> Unit)? = null): Pair<String, Pair<String?, String?>> = withContext(Dispatchers.IO) {
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

        if (mode == PrismSettings.AI_MODE_CLOUD) {
            // Priority 1: Check for triggers (image/video)
            if (userText.contains("generate image", ignoreCase = true) || userText.contains("make a picture", ignoreCase = true)) {
                val uri = ImageGenManager.generateImage(context, userText)
                return@withContext Pair("I've generated a high-fidelity image for you.", Pair(uri?.toString(), "image"))
            }

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
            val modelPath = PrismSettings.getLocalAiModelPath()
            if (modelPath.isBlank()) return@withContext Pair("Error: No local model selected. Please download or select one in Settings.", Pair(null, null))

            val response = if (streaming) {
                LocalAiService.generateResponseStreaming(context, modelPath, userText, maxTokens, onToken!!, onReasoning)
            } else {
                LocalAiService.generateResponse(context, modelPath, userText)
            }
            return@withContext Pair(response, Pair(null, null))
        }
    }

    /**
     * Sends a prompt to a peer hosting a model over the Prism mesh (see [P2pModelRegistry] /
     * [com.prism.launcher.vpn.PrismAiHost]) and streams the response back. Reuses the exact same
     * transport as P2P website hosting — [PrismSocket]'s PRISM_CONNECT handshake into port 8080 —
     * just with the reserved [P2pModelRegistry.MODEL_HOST_DOMAIN] marker instead of a real
     * hosted-site domain, and a bespoke `POST /generate` instead of a GET for a file.
     */
    private fun fetchP2pModelResponse(peerIp: String, userText: String, onToken: ((String) -> Unit)?): String {
        var socket: PrismSocket? = null
        return try {
            socket = PrismSocket()
            socket.setHostHint(P2pModelRegistry.MODEL_HOST_DOMAIN)
            socket.connect(InetSocketAddress(peerIp, 8080), 10000)

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
