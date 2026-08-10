package com.prism.launcher.messaging

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes network requests to OpenAI-compatible cloud LLM endpoints.
 * Also includes mock logic for image/video generation demonstration.
 */
object CloudAiService {

    /**
     * Top-level entry point for AI generation.
     * Returns Pair(ResponseText, Pair(AttachmentUri, AttachmentType))
     */
    suspend fun generateResponse(prompt: String): Pair<String, Pair<String?, String?>> {
        // In a real implementation, this would call your Cloud LLM API (OpenAI/Gemini/etc)
        // and parse the response for tool calls (like image generators).
        
        val responseText: String
        var attachmentUri: String? = null
        var attachmentType: String? = null

        if (prompt.contains("image", ignoreCase = true)) {
            responseText = "I've generated a high-fidelity vision of your request using our cloud neural engine."
            attachmentUri = "https://picsum.photos/1200/800" // Mock image
            attachmentType = "image"
        } else if (prompt.contains("video", ignoreCase = true)) {
            responseText = "Here is an AI-synthesized motion sequence based on your prompt."
            attachmentUri = "https://www.w3schools.com/html/mov_bbb.mp4" // Mock video
            attachmentType = "video"
        } else if (prompt.contains("who are you", ignoreCase = true)) {
            responseText = "I am Sam, your advanced AI companion powered by the Prism Cloud Engine."
        } else {
            responseText = "Sam (Cloud): I processed your request. Our cloud infrastructure provides higher accuracy and multi-modal support compared to local inference."
        }

        return Pair(responseText, Pair(attachmentUri, attachmentType))
    }

    /**
     * Legacy/Internal helper for real API calls.
     */
    fun fetchResponse(baseUrl: String, apiKey: String, model: String, userText: String, base64Image: String? = null): String {
        return try {
            val url = URL(baseUrl + "chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val messages = JSONArray()
            val userMessage = JSONObject()
            userMessage.put("role", "user")

            if (base64Image == null) {
                userMessage.put("content", userText)
            } else {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText)
                })
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
                userMessage.put("content", contentArray)
            }
            messages.put(userMessage)

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseString)
                val choices = responseJson.getJSONArray("choices")
                if (choices.length() > 0) {
                    choices.getJSONObject(0).getJSONObject("message").getString("content")
                } else {
                    "Error: No response choices found."
                }
            } else {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                PrismPlatform.log.error("CloudAiService", "Cloud Error ($responseCode) from $url: $errorString")
                "Cloud Error ($responseCode): $errorString"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "chat/completions", e)
            "Network Error: ${e.message}"
        }
    }

    /**
     * Streaming variant of [fetchResponse]: uses Server-Sent Events (`"stream": true`) and
     * invokes [onToken] with each incremental piece of text as it arrives. [maxTokens] <= 0
     * omits the `max_tokens` field entirely, letting the provider generate until it stops on
     * its own. Still returns the full accumulated response text once the stream ends.
     */
    fun fetchResponseStreaming(baseUrl: String, apiKey: String, model: String, userText: String, base64Image: String? = null, maxTokens: Int = -1, onToken: (String) -> Unit): String {
        return try {
            val url = URL(baseUrl + "chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val messages = JSONArray()
            val userMessage = JSONObject()
            userMessage.put("role", "user")

            if (base64Image == null) {
                userMessage.put("content", userText)
            } else {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText)
                })
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
                userMessage.put("content", contentArray)
            }
            messages.put(userMessage)

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
            body.put("stream", true)
            if (maxTokens > 0) body.put("max_tokens", maxTokens)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                val error = "Cloud Error ($responseCode): $errorString"
                PrismPlatform.log.error("CloudAiService", "Cloud Error ($responseCode) from $url: $errorString")
                onToken(error)
                return error
            }

            val accumulator = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunk = JSONObject(data)
                        val choices = chunk.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                        val piece = delta.optString("content", "")
                        if (piece.isNotEmpty()) {
                            accumulator.append(piece)
                            onToken(piece)
                        }
                    } catch (e: Exception) {
                        // Skip malformed/partial SSE chunks rather than aborting the whole stream
                    }
                }
            }

            if (accumulator.isEmpty()) "Error: No response received from stream." else accumulator.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "chat/completions", e)
            val error = "Network Error: ${e.message}"
            onToken(error)
            error
        }
    }

    /**
     * Ollama's native chat endpoint (not the OpenAI-compat shim) — used for "Local Cloud" mode
     * against a LAN-discovered [com.prism.launcher.messaging.OllamaDiscoveryService.OllamaServer].
     * Streams newline-delimited JSON (NDJSON, one `{message:{content}, done}` object per line),
     * not Server-Sent Events like [fetchResponseStreaming] — a different wire format, hence a
     * sibling function rather than reusing the SSE parser.
     */
    fun fetchOllamaChatStreaming(host: String, port: Int, model: String, userText: String, onToken: (String) -> Unit): String {
        return try {
            val url = URL("http://$host:$port/api/chat")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
            body.put("stream", true)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                val error = "Ollama Error ($responseCode): $errorString"
                PrismPlatform.log.error("CloudAiService", "Ollama Error ($responseCode) from $url: $errorString")
                onToken(error)
                return error
            }

            val accumulator = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val chunk = JSONObject(line)
                        val piece = chunk.optJSONObject("message")?.optString("content", "") ?: ""
                        if (piece.isNotEmpty()) {
                            accumulator.append(piece)
                            onToken(piece)
                        }
                        if (chunk.optBoolean("done", false)) break
                    } catch (e: Exception) {
                        // Skip malformed/partial NDJSON lines rather than aborting the whole stream
                    }
                }
            }

            if (accumulator.isEmpty()) "Error: No response received from Ollama." else accumulator.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling http://$host:$port/api/chat", e)
            val error = "Network Error: ${e.message}"
            onToken(error)
            error
        }
    }

    /**
     * Non-streaming variant of [fetchOllamaChatStreaming] — same NDJSON parsing, just
     * accumulated silently instead of invoking a per-token callback.
     */
    fun fetchOllamaChat(host: String, port: Int, model: String, userText: String): String =
        fetchOllamaChatStreaming(host, port, model, userText) { }

    /**
     * Non-streaming chat completion carrying a full `messages` history and an optional `tools`
     * array (OpenAI-style function-calling) -- used only by [com.prism.launcher.agentic.AgenticEngine]'s
     * tool-calling loop, where intermediate rounds (deciding whether to call a tool) aren't shown
     * to the user token-by-token anyway. Returns the raw `message` object from the response
     * (either `{"content":...}` or `{"tool_calls":[...]}`) for the caller to interpret; network/
     * HTTP failures come back as a synthetic `{"content":"...error text..."}` message so callers
     * can treat every outcome as a normal chat turn rather than special-casing exceptions.
     */
    fun fetchChatWithTools(baseUrl: String, apiKey: String, model: String, messages: JSONArray, tools: JSONArray?): JSONObject {
        return try {
            val url = URL(baseUrl + "chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000

            val body = JSONObject().put("model", model).put("messages", messages)
            if (tools != null && tools.length() > 0) body.put("tools", tools)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val choices = JSONObject(responseString).optJSONArray("choices")
                choices?.optJSONObject(0)?.optJSONObject("message")
                    ?: JSONObject().put("content", "Error: No response choices found.")
            } else {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                PrismPlatform.log.error("CloudAiService", "Cloud Error ($responseCode) from $url: $errorString")
                JSONObject().put("content", "Cloud Error ($responseCode): $errorString")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "chat/completions", e)
            JSONObject().put("content", "Network Error: ${e.message}")
        }
    }

    /**
     * Non-streaming Ollama chat carrying a full `messages` history and an optional `tools` array
     * -- Ollama deliberately mirrors OpenAI's `tools`/`tool_calls` shape. Sibling to
     * [fetchChatWithTools] for the same tool-calling-loop use case; see that function's doc for
     * why this is non-streaming and returns a raw message object rather than throwing.
     */
    fun fetchOllamaChatWithTools(host: String, port: Int, model: String, messages: JSONArray, tools: JSONArray?): JSONObject {
        return try {
            val url = URL("http://$host:$port/api/chat")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 20000
            // Non-streaming, so the caller sees nothing until this returns -- and unlike a Cloud
            // API, local Ollama inference on consumer hardware can genuinely take minutes: a cold
            // model load (Ollama unloads after ~5min idle) plus a growing tool schema (every
            // enabled tool's full JSON Schema is serialized into every request) both add real
            // prefill/load time before generation even starts. 30s was cutting that off mid-response.
            conn.readTimeout = 180000

            val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
            if (tools != null && tools.length() > 0) body.put("tools", tools)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseString).optJSONObject("message")
                    ?: JSONObject().put("content", "Error: No message in Ollama response.")
            } else {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                PrismPlatform.log.error("CloudAiService", "Ollama Error ($responseCode) from $url: $errorString")
                JSONObject().put("content", "Ollama Error ($responseCode): $errorString")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling http://$host:$port/api/chat", e)
            JSONObject().put("content", "Network Error: ${e.message}")
        }
    }

    /**
     * Calls a DALL-E 3 compatible endpoint to generate an image.
     *
     * Returns a [PrismImage] rather than an Android Bitmap -- the portable raster type the rest
     * of Prism already uses for exactly this reason. Android turns it back into a Bitmap at the
     * point of display, which is where the conversion belongs.
     */
    fun fetchImage(baseUrl: String, apiKey: String, prompt: String): PrismImage? {
        return try {
            val url = URL(baseUrl + "images/generations")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject()
            body.put("model", "dall-e-3")
            body.put("prompt", prompt)
            body.put("n", 1)
            body.put("size", "1024x1024")
            body.put("response_format", "b64_json")

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseString)
                val data = responseJson.getJSONArray("data")
                if (data.length() > 0) {
                    val b64 = data.getJSONObject(0).getString("b64_json")
                    // java.util.Base64, not android.util.Base64. The DEFAULT flag on Android's
                    // decoder is plain RFC 4648, which is what getDecoder() does, so the bytes
                    // are identical.
                    val bytes = java.util.Base64.getDecoder().decode(b64)
                    PrismPlatform.images.decode(bytes)
                } else null
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText()
                PrismPlatform.log.error("CloudAi", "Image Gen Failed: $error")
                PrismPlatform.log.error("CloudAiService", "Cloud Error ($responseCode) from $url (image gen): $error")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "images/generations", e)
            null
        }
    }
}
