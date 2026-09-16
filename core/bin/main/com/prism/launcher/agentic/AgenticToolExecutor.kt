package com.prism.launcher.agentic

import com.prism.core.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Runs a single [ToolDefinition] with model-supplied JSON arguments and returns the raw result
 * text to feed back to the model. Never throws -- failures come back as an error string, same
 * convention CloudAiService uses for its own network calls. */
object AgenticToolExecutor {

    private const val MAX_RESULT_CHARS = 4000
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 15000

    suspend fun execute(tool: ToolDefinition, argumentsJson: String): String {
        return try {
            when (val exec = tool.executor) {
                is ToolExecutorConfig.Builtin -> AgenticBuiltinTools.execute(exec.id, argumentsJson)
                is ToolExecutorConfig.Http -> executeHttp(exec, argumentsJson)
            }
        } catch (e: Exception) {
            "Error running tool '${tool.name}': ${e.message}"
        }
    }

    private fun executeHttp(exec: ToolExecutorConfig.Http, argumentsJson: String): String {
        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }

        val url = URL(substitute(exec.url, args, urlEncode = true))
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = exec.method.ifBlank { "GET" }.uppercase()
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            exec.headers.forEach { (key, value) -> conn.setRequestProperty(key, substitute(value, args, urlEncode = false)) }

            val method = conn.requestMethod
            if (exec.bodyTemplate.isNotBlank() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                val body = substitute(exec.bodyTemplate, args, urlEncode = false)
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }

            val responseCode = conn.responseCode
            val text = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                "HTTP $responseCode: $err"
            }
            if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "... (truncated)" else text
        } finally {
            conn.disconnect()
        }
    }

    /** Replaces every `{argName}` in [template] with that argument's value from [args].
     * Unrecognized placeholders (no matching argument) are left as-is rather than erroring, so a
     * literal `{` in a URL/body that isn't actually a placeholder doesn't break the request. */
    private fun substitute(template: String, args: JSONObject, urlEncode: Boolean): String {
        var result = template
        args.keys().forEach { key ->
            val value = args.opt(key)?.toString() ?: ""
            val replacement = if (urlEncode) URLEncoder.encode(value, "UTF-8") else value
            result = result.replace("{$key}", replacement)
        }
        return result
    }
}
