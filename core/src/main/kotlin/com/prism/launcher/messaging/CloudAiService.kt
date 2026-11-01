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
 *
 * ## The mock that used to be here
 *
 * This object used to carry `generateResponse(prompt)`, which answered ANY prompt containing
 * "video" with a canned sentence plus a link to w3schools' sample MP4, and answered everything
 * else with invented prose about "our cloud neural engine". Sam's cloud mode called it directly
 * for video prompts, so a request for a video produced a stranger's demo file presented as model
 * output. It is deleted rather than kept for demos: every path that wants an answer now goes to a
 * real endpoint, and a fake one cannot be reached by accident.
 */
object CloudAiService {

    /**
     * Builds an endpoint URL from a base that may or may not end in a slash.
     *
     * The image path used `baseUrl + "images/generations"`, so a base without its trailing slash
     * produced `https://host/v1images/generations` -- a 404 that looked like the provider refusing
     * the request.
     */
    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /** Timeouts on every cloud call, so a provider that accepts the connection and stalls cannot
     * hold a chat turn open forever. Read is generous because a long generation is not a stall. */
    private fun open(url: java.net.URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
        }

    /**
     * Legacy/Internal helper for real API calls.
     */
    fun fetchResponse(
        baseUrl: String,
        apiKey: String,
        model: String,
        userText: String,
        base64Image: String? = null,
        json: Boolean = false,
        temperature: Float? = null,
    ): String {
        return try {
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "chat/completions"))
            val conn = open(url)
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
            if (temperature != null) body.put("temperature", temperature.toDouble())
            // Server-side JSON mode. The local path constrains the sampler with a GBNF grammar;
            // an OpenAI-compatible endpoint has no grammar, so this is the equivalent request and
            // the planner still validates the reply against the schema.
            if (json) body.put("response_format", JSONObject().put("type", "json_object"))

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
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "/chat/completions", e)
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
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "chat/completions"))
            val conn = open(url)
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
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "/chat/completions", e)
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
    fun fetchOllamaChatStreaming(
        host: String,
        port: Int,
        model: String,
        userText: String,
        onToken: (String) -> Unit,
        json: Boolean = false,
        temperature: Float? = null,
    ): String {
        return try {
            val url = com.prism.core.Urls.of("http://$host:$port/api/chat")
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
            // Ollama calls this "format": "json". It is the grammar hook a local server has;
            // it constrains decoding rather than merely asking nicely in the prompt.
            if (json) body.put("format", "json")
            if (temperature != null) {
                body.put("options", JSONObject().put("temperature", temperature.toDouble()))
            }

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
    fun fetchOllamaChat(
        host: String,
        port: Int,
        model: String,
        userText: String,
        json: Boolean = false,
        temperature: Float? = null,
    ): String = fetchOllamaChatStreaming(host, port, model, userText, { }, json, temperature)

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
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "chat/completions"))
            val conn = open(url)
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
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl" + "/chat/completions", e)
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
            val url = com.prism.core.Urls.of("http://$host:$port/api/chat")
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
     * What one image request produced, and whether asking again could help.
     *
     * A 404 for a model the provider does not serve will never become an image, while a read
     * timeout or a 503 means the endpoint is still working. VideoCraft needs that distinction: a
     * transient failure should cost one frame, a definitive one should stop asking for the rest of
     * the film.
     */
    data class ImageResult(
        val image: PrismImage?,
        val retryable: Boolean,
        val detail: String,
    )

    /**
     * Asks an OpenAI-compatible endpoint for one image, as a [PrismImage].
     *
     * [modelId] IS THE PROFILE'S OWN MODEL, not a literal. This used to send `"dall-e-3"` no matter
     * which provider the user configured, so a Gemini or local OpenAI-compatible endpoint answered
     * 404 ("models/dall-e-3 is not found") on every single frame and VideoCraft fell back to the
     * procedural renderer -- the cloud model was never once asked for the image it was selected to
     * make.
     *
     * [timeoutMs] is the READ timeout, so a slow generation waits rather than failing; [attempts]
     * asks again after a retryable failure instead of declaring the whole endpoint dead. VideoCraft
     * passes several attempts because a keyframe is the one request in this app where arriving late
     * is strictly better than arriving wrong.
     */
    fun fetchImage(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        modelId: String = "dall-e-3",
        timeoutMs: Int = 120_000,
        attempts: Int = 1,
        /**
         * The size to ask for, or null for the endpoint's own default.
         *
         * LAST AND NAMED rather than beside the prompt, because every existing caller passes the
         * model id positionally and moving that argument would quietly reinterpret their call as a
         * request for a 1024x1024 render of a model name.
         */
        size: ImageSize? = null,
    ): PrismImage? = fetchImageResult(baseUrl, apiKey, prompt, modelId, timeoutMs, attempts, size).image

    /**
     * One interpolated frame from a cloud model, or the reason there is not one.
     *
     * `retryable` separates "this endpoint does not interpolate" from "this frame did not arrive". The
     * first is remembered for the rest of the film; the second costs one frame and the next one asks
     * again, because a slow GPU behind a queue is not a refusal.
     */
    data class InterpolationResult(
        val image: com.prism.core.PrismImage? = null,
        val retryable: Boolean = false,
        val detail: String = "",
    )

    /**
     * Asks a cloud model for the frame [t] of the way from [first] to [second].
     *
     * Both frames are base64 PNGs; the answer is read from `image`, `data[0].b64_json` or `b64_png`,
     * whichever the endpoint uses, and decoded with the core's own PNG reader. A body that is not JSON
     * at all is treated as a refusal with the first line of it in the detail, because that is what a
     * provider's HTML error page looks like and it is worth seeing in the log.
     */
    fun fetchInterpolation(
        baseUrl: String,
        apiKey: String,
        first: String,
        second: String,
        t: Double,
        modelId: String = "interpolation",
        timeoutMs: Int = 120_000,
    ): InterpolationResult {
        return try {
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "video/interpolate"))
            val conn = open(url)
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject()
            body.put("model", modelId)
            body.put("t", t)
            body.put("first", first)
            body.put("second", second)
            body.put("format", "png")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                return InterpolationResult(
                    retryable = status >= 500 || status == 429,
                    detail = "HTTP $status" + detail.take(160).let { if (it.isBlank()) "" else ": $it" },
                )
            }
            val text = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return InterpolationResult(detail = text.take(120))
            val encoded = json.optString("image").takeIf { it.isNotEmpty() }
                ?: json.optString("b64_png").takeIf { it.isNotEmpty() }
                ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")?.takeIf { it.isNotEmpty() }
                ?: return InterpolationResult(detail = "the answer carried no frame")
            val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: return InterpolationResult(detail = "the answer's frame was not base64")
            val image = decodeImage(bytes)
                ?: return InterpolationResult(detail = "the answer's frame did not decode")
            InterpolationResult(image = image)
        } catch (t2: Throwable) {
            InterpolationResult(
                retryable = true,
                detail = t2.message ?: t2::class.simpleName ?: "the request failed",
            )
        }
    }

    /** A PNG (or JPEG) from bytes, through the platform codec with a PNG fallback in the core. */
    private fun decodeImage(bytes: ByteArray): com.prism.core.PrismImage? {
        val file = java.io.File.createTempFile("prism-cloud-frame", ".png")
        return try {
            file.writeBytes(bytes)
            com.prism.core.PrismPlatform.images.decode(file, 0)
                ?: com.prism.core.RasterCodec.decode(file, 0)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * What a cloud audio endpoint sent back.
     *
     * `pcm` is raw little-endian 16-bit samples and `wav` is a whole RIFF file: providers do both, and
     * the caller decodes whichever arrived. Neither means the endpoint answered with no audio, which is
     * a refusal when [retryable] is false and a slow endpoint when it is true.
     */
    data class AudioBed(
        val pcm: ByteArray? = null,
        val wav: ByteArray? = null,
        val channels: Int = 1,
        val retryable: Boolean = false,
        val detail: String = "",
    )

    /**
     * Asks a cloud audio model for a music bed.
     *
     * The route is `{baseUrl}/audio/generations`, the audio counterpart of the `images/generations` route
     * the image path already uses, with `{model, prompt, seconds, response_format}` in the body. Three
     * answer shapes are accepted, because they are the three that appear in the wild: JSON with base64
     * `data[0].b64_pcm` (or `b64_json` holding a WAV), JSON with a base64 `audio` string, and a raw audio
     * body. Anything else is a refusal and says so, which is the difference between "this endpoint does
     * not render music" and a film that silently loses its bed.
     */
    fun fetchAudioBed(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        modelId: String = "music",
        seconds: Float = 8f,
        timeoutMs: Int = 60_000,
    ): AudioBed {
        return try {
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "audio/generations"))
            val conn = open(url)
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "audio/wav, audio/pcm, application/json")
            conn.doOutput = true

            val body = JSONObject()
            body.put("model", modelId)
            body.put("prompt", prompt)
            body.put("seconds", seconds.toDouble())
            body.put("response_format", "pcm16")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                val retryable = status >= 500 || status == 429
                return AudioBed(
                    retryable = retryable,
                    detail = "HTTP $status" + detail.take(160).let { if (it.isBlank()) "" else ": $it" },
                )
            }

            val contentType = conn.contentType.orEmpty().lowercase()
            val bytes = conn.inputStream.readBytes()
            if (contentType.contains("json") || bytes.firstOrNull() == '{'.code.toByte()) {
                val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    ?: return AudioBed(detail = "the endpoint's answer was not JSON")
                val entry = json.optJSONArray("data")?.optJSONObject(0)
                val encoded = entry?.optString("b64_pcm")?.takeIf { it.isNotEmpty() }
                    ?: entry?.optString("b64_json")?.takeIf { it.isNotEmpty() }
                    ?: json.optString("audio").takeIf { it.isNotEmpty() }
                    ?: json.optString("b64_pcm").takeIf { it.isNotEmpty() }
                    ?: return AudioBed(detail = "the answer carried no audio")
                val decoded = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
                    ?: return AudioBed(detail = "the answer's audio was not base64")
                val asWav = decoded.size > 12 && String(decoded, 0, 4, Charsets.US_ASCII) == "RIFF"
                val channels = json.optInt("channels", 1)
                return if (asWav) AudioBed(wav = decoded, channels = channels)
                else AudioBed(pcm = decoded, channels = channels)
            }
            val asWav = bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
            return if (asWav) AudioBed(wav = bytes)
            else AudioBed(pcm = bytes, channels = if (contentType.contains("stereo")) 2 else 1)
        } catch (t: Throwable) {
            return AudioBed(retryable = true, detail = t.message ?: t::class.simpleName ?: "the request failed")
        }
    }

    /**
     * [cancel] is asked between attempts, not inside one.
     *
     * A blocking read cannot be abandoned from another thread, so a request already in flight runs to
     * its own timeout; what this stops is paying for another one. VideoCraft passes its cancel flag and
     * a cancelled call comes back with `retryable = false` and a detail that says so, which is not a
     * failure of the endpoint and must not be reported as one.
     */
    fun fetchImageResult(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        modelId: String = "dall-e-3",
        timeoutMs: Int = 120_000,
        attempts: Int = 1,
        size: ImageSize? = null,
        cancel: (() -> Boolean)? = null,
    ): ImageResult {
        var last = ImageResult(null, retryable = false, detail = "no attempt was made")
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (cancel?.invoke() == true) {
                return ImageResult(null, retryable = false, detail = "cancelled")
            }
            last = fetchImageOnce(baseUrl, apiKey, prompt, modelId, timeoutMs, size)
            if (last.image != null || !last.retryable) return last
            if (cancel?.invoke() == true) {
                return ImageResult(null, retryable = false, detail = "cancelled")
            }
            if (attempt + 1 < attempts) {
                // A widening pause between attempts, so a cold endpoint or a rate limit is given
                // time to clear instead of being hammered. It is deliberately short next to the
                // read timeout: the waiting happens inside the request, not here.
                runCatching { Thread.sleep(3_000L * (attempt + 1)) }
            }
        }
        return last
    }

    private fun fetchImageOnce(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        modelId: String,
        timeoutMs: Int,
        size: ImageSize?,
    ): ImageResult {
        return try {
            val url = com.prism.core.Urls.of(endpoint(baseUrl, "images/generations"))
            val conn = open(url)
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject()
            body.put("model", modelId.ifBlank { "dall-e-3" })
            body.put("prompt", prompt)
            body.put("n", 1)
            // The user's resolution when ImageCraft asked for one; the old fixed square otherwise.
            body.put("size", size?.stored ?: "1024x1024")
            body.put("response_format", "b64_json")

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val image = imageFromResponse(JSONObject(responseString), timeoutMs)
                ImageResult(
                    image = image,
                    // A 200 without an image is the endpoint's answer, not a stall.
                    retryable = false,
                    detail = if (image == null) "the endpoint answered 200 without an image" else "ok",
                )
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText()
                PrismPlatform.log.error("CloudAi", "Image Gen Failed: $error")
                PrismPlatform.log.error("CloudAiService", "Cloud Error ($responseCode) from $url (image gen): $error")
                ImageResult(
                    image = null,
                    retryable = responseCode in RETRYABLE_STATUS,
                    detail = "HTTP $responseCode",
                )
            }
        } catch (e: Exception) {
            PrismPlatform.log.error("CloudAiService", "Network error calling $baseUrl images/generations", e)
            ImageResult(
                image = null,
                // Network failures are timeouts, resets and half-open sockets: all worth waiting
                // out. A malformed response is not, but that is handled before this catch.
                retryable = e is java.io.IOException,
                detail = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    /**
     * Reads an image out of an image-generation response.
     *
     * BOTH SHAPES. Many OpenAI-compatible image endpoints ignore `response_format` and answer with
     * a `url` instead of `b64_json`. The old code read only `b64_json` and threw on the other shape,
     * which VideoCraft then recorded as the endpoint refusing every frame -- while the provider had
     * in fact finished diffusing the image. Downloading the link is what lets that keyframe arrive.
     */
    private fun imageFromResponse(response: JSONObject, timeoutMs: Int): PrismImage? {
        val data = response.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val first = data.optJSONObject(0) ?: return null
        // `optString` returns the literal "null" for a JSON null in this JSON implementation, so a
        // provider that sends `"b64_json": null` and a URL must not be treated as inline base64.
        val inline = first.optString("b64_json").takeUnless { it.isBlank() || it == "null" }
            ?: first.optString("image").takeUnless { it.isBlank() || it == "null" }.orEmpty()
        if (inline.isNotBlank()) {
            val clean = inline.substringAfter("base64,", inline)
            // java.util.Base64, not android.util.Base64. The DEFAULT flag on Android's decoder is
            // plain RFC 4648, which is what getDecoder() does, so the bytes are identical.
            val bytes = runCatching { java.util.Base64.getDecoder().decode(clean) }.getOrNull()
                ?: return null
            return PrismPlatform.images.decode(bytes)
        }
        val link = first.optString("url").takeUnless { it.isBlank() || it == "null" }
        if (link != null) return downloadImage(link, timeoutMs)
        return null
    }

    /** Fetches an image the endpoint returned as a link. */
    private fun downloadImage(link: String, timeoutMs: Int): PrismImage? = try {
        val conn = open(com.prism.core.Urls.of(link))
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        if (conn.responseCode == 200) {
            val bytes = conn.inputStream.use { it.readBytes() }
            PrismPlatform.images.decode(bytes)
        } else {
            PrismPlatform.log.error("CloudAiService", "Image link answered ${conn.responseCode}: $link")
            null
        }
    } catch (e: Exception) {
        PrismPlatform.log.error("CloudAiService", "Could not download the image at $link", e)
        null
    }

    private val RETRYABLE_STATUS = setOf(408, 409, 425, 429, 500, 502, 503, 504)
}
