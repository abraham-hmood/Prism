package com.prism.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks whether a cloud model's base URL is actually an OpenAI-compatible endpoint.
 *
 * ## What counts as working
 *
 * A REFUSAL IS A SUCCESS. `401 Unauthorized` and `403 Forbidden` mean the server is there, speaks
 * this protocol, and wants a key -- which is exactly the state a user is in while typing a URL
 * before pasting their key. Treating those as failures would reject every correct URL. `405 Method
 * Not Allowed` counts too: it is what a chat-completions path returns to a GET, and it proves the
 * route exists.
 *
 * What does not count is `404`, which means nothing is served there, and any transport failure --
 * unknown host, refused connection, TLS error -- which means the address is wrong or unreachable.
 *
 * ## Why it retries without /chat/completions
 *
 * Prism stores a BASE url and appends the path itself, but the URL people have to hand is usually
 * the full completions endpoint, because that is what every provider's documentation shows. Pasting
 * it produces a base that Prism then extends into `.../chat/completions/chat/completions`, which
 * 404s with no hint as to why. Rather than making the user work that out, the probe tries the
 * shortened form and reports the URL that actually answered, so the field can be corrected to it.
 */
object CloudEndpointProbe {

    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        /**
         * @param url the form that answered, which may differ from what was typed
         * @param corrected true when [url] is not what the user entered
         */
        data class Ok(val url: String, val detail: String, val corrected: Boolean) : Result()

        data class Failed(val reason: String) : Result()
    }

    /** Codes that prove something is listening and speaking HTTP at this path. */
    private fun isAlive(code: Int): Boolean =
        code in 200..299 || code == 401 || code == 403 || code == 405 || code == 400

    /**
     * Probes [rawUrl], then the same URL with a trailing `/chat/completions` removed.
     *
     * Blocking; call it off the main thread.
     */
    fun probe(rawUrl: String, apiKey: String? = null): Result {
        val typed = rawUrl.trim()
        if (typed.isEmpty()) return Result.Failed("Enter a URL.")
        if (!typed.startsWith("http://", true) && !typed.startsWith("https://", true)) {
            return Result.Failed("The URL must start with http:// or https://")
        }

        val stripped = stripCompletions(typed)
        val candidates = if (stripped != null) listOf(typed, stripped) else listOf(typed)

        var lastReason = "Could not reach that URL."
        for ((index, candidate) in candidates.withIndex()) {
            when (val outcome = attempt(candidate, apiKey)) {
                is Result.Ok -> return outcome.copy(corrected = index > 0)
                is Result.Failed -> lastReason = outcome.reason
            }
        }
        return Result.Failed(lastReason)
    }

    /**
     * One candidate, tried two ways.
     *
     * The `models` listing is checked first because it is the canonical OpenAI-compatible liveness
     * probe and answers to a GET. If that is absent -- plenty of proxies expose only the completions
     * route -- the candidate itself is tried, where a 405 is still proof of life.
     */
    private fun attempt(url: String, apiKey: String?): Result {
        val base = if (url.endsWith("/")) url else "$url/"

        val modelsCode = statusOf(base + "models", apiKey)
        if (modelsCode != null && isAlive(modelsCode)) {
            return Result.Ok(url, describe(modelsCode), corrected = false)
        }

        val directCode = statusOf(url, apiKey)
        if (directCode != null && isAlive(directCode)) {
            return Result.Ok(url, describe(directCode), corrected = false)
        }

        return Result.Failed(
            when {
                modelsCode == null && directCode == null ->
                    "Could not connect. Check the address and your network."
                directCode == 404 || modelsCode == 404 ->
                    "The server answered, but nothing is served at that path (404)."
                else ->
                    "The server answered with ${directCode ?: modelsCode}, which is not an " +
                        "OpenAI-compatible endpoint."
            }
        )
    }

    /** The status code, or null when the request could not be made at all. */
    private fun statusOf(url: String, apiKey: String?): Int? = runCatching {
        val builder = Request.Builder().url(url).get()
        // Sent when present so a server that would answer 200 for an authorised caller does, rather
        // than 401 -- both are accepted, but the clearer signal is worth having.
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        http.newCall(builder.build()).execute().use { it.code }
    }.getOrNull()

    private fun describe(code: Int): String = when (code) {
        401, 403 -> "Reachable — the server is asking for an API key."
        405 -> "Reachable — the endpoint exists."
        400 -> "Reachable — the server rejected an empty request, which is expected."
        else -> "Reachable."
    }

    /**
     * Drops a trailing `chat/completions`, with or without a trailing slash.
     *
     * Returns null when there is nothing to strip, so the caller does not probe the same URL twice.
     */
    private fun stripCompletions(url: String): String? {
        val trimmed = url.trimEnd('/')
        val suffix = "/chat/completions"
        if (!trimmed.endsWith(suffix, ignoreCase = true)) return null
        return trimmed.dropLast(suffix.length) + "/"
    }
}
