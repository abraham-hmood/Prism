package com.prism.launcher.agentic

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * Finds tool calls in the prose of a model that was never trained to make them.
 *
 * ## Why a second parser
 *
 * [SyntaxToolFormat] reads exactly the markup the prompt asked for. A model built for tool calling
 * produces it; a model that was not -- a small chat GGUF, a base model behind an OpenAI-compatible
 * proxy, an Ollama model with no tools template -- understands the INSTRUCTION but answers in
 * whatever shape its training made familiar: a fenced ```json block, a bare object, Mistral's
 * `[TOOL_CALLS]` array, Llama 3.1's `<function=name>` tag, or a ReAct "Action / Action Input" pair.
 * Every one of those is an unambiguous request to run a tool, and treating them as a final answer is
 * how a non-agentic model "could not use tools" -- it did use them; Prism did not listen.
 *
 * ## Why it cannot misfire on ordinary answers
 *
 * A candidate only counts when its name is one of the tools the model was actually offered. A user
 * asking for "a JSON example with a name field" gets their JSON back as an answer, because no tool
 * is called whatever the example's `name` happens to be -- unless it names a real tool, in which case
 * the model was asking for that tool in the only way it knew.
 */
object LenientToolCalls {

    /** One recognised call and the span of text it occupied, so the span can be removed. */
    data class Found(val call: ToolCall, val range: IntRange)

    fun extract(rawText: String, toolNames: Collection<String>): List<ToolCall> =
        find(rawText, toolNames).map { it.call }

    /** [rawText] with every recognised call removed, for displaying what remains as prose. */
    fun strip(rawText: String, toolNames: Collection<String>): String {
        val found = find(rawText, toolNames)
        if (found.isEmpty()) return rawText.trim()
        val out = StringBuilder()
        var at = 0
        for (f in found.sortedBy { it.range.first }) {
            if (f.range.first < at) continue
            out.append(rawText, at, f.range.first)
            at = f.range.last + 1
        }
        if (at < rawText.length) out.append(rawText, at, rawText.length)
        return out.toString()
            .replace(Regex("```(?:json|tool_call|tool)?\\s*```"), "")
            .replace("[TOOL_CALLS]", "")
            .trim()
    }

    fun find(rawText: String, toolNames: Collection<String>): List<Found> {
        if (rawText.isBlank() || toolNames.isEmpty()) return emptyList()
        val byKey = toolNames.associateBy { key(it) }
        val found = ArrayList<Found>()
        val taken = ArrayList<IntRange>()
        fun claim(range: IntRange, call: ToolCall) {
            if (taken.any { it.first <= range.last && range.first <= it.last }) return
            taken += range
            found += Found(call, range)
        }

        // Llama 3.1: <function=name>{...}</function>
        Regex("(?s)<function=([A-Za-z0-9_\\-. ]+)>(.*?)</function>").findAll(rawText).forEach { m ->
            val name = byKey[key(m.groupValues[1])] ?: return@forEach
            claim(m.range, ToolCall(null, name, argumentsOf(m.groupValues[2].trim())))
        }

        // ReAct: "Action: name" followed by "Action Input: {...}"
        Regex("(?is)Action\\s*:\\s*`?([A-Za-z0-9_\\-]+)`?\\s*\\n\\s*Action\\s*Input\\s*:\\s*").findAll(rawText).forEach { m ->
            val name = byKey[key(m.groupValues[1])] ?: return@forEach
            val start = m.range.last + 1
            val (json, end) = balancedAt(rawText, start) ?: (null to start - 1)
            val range = m.range.first..maxOf(end, m.range.last)
            claim(range, ToolCall(null, name, json?.let { argumentsOf(it) } ?: "{}"))
        }

        // Any balanced JSON object or array anywhere: fenced, tagged, bare or after [TOOL_CALLS].
        var i = 0
        while (i < rawText.length) {
            val c = rawText[i]
            if (c != '{' && c != '[') { i++; continue }
            val span = balancedAt(rawText, i)
            if (span == null) { i++; continue }
            val (text, end) = span
            val calls = callsIn(text, byKey)
            if (calls.isNotEmpty()) {
                val range = i..end
                if (taken.none { it.first <= range.last && range.first <= it.last }) {
                    taken += range
                    calls.forEachIndexed { index, call ->
                        // Every call in one array shares its span; only the first owns it for stripping.
                        found += Found(call, if (index == 0) range else IntRange.EMPTY)
                    }
                }
                i = end + 1
            } else {
                i++
            }
        }
        return found.sortedBy { if (it.range.isEmpty()) Int.MAX_VALUE else it.range.first }
    }

    /** The calls a JSON value describes, in any of the shapes models produce. */
    private fun callsIn(text: String, byKey: Map<String, String>): List<ToolCall> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { idx ->
                array.optJSONObject(idx)?.let { callOf(it, byKey) }
            }
        }
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        // {"tool_calls": [...]} -- a model imitating the OpenAI response it has seen in training.
        obj.optJSONArray("tool_calls")?.let { array ->
            return (0 until array.length()).mapNotNull { idx ->
                array.optJSONObject(idx)?.let { callOf(it, byKey) }
            }
        }
        return listOfNotNull(callOf(obj, byKey))
    }

    private val NAME_FIELDS = listOf("name", "tool", "tool_name", "function", "action", "command", "call")
    private val ARGUMENT_FIELDS = listOf("arguments", "parameters", "args", "input", "action_input", "params", "kwargs")

    private fun callOf(obj: JSONObject, byKey: Map<String, String>): ToolCall? {
        // {"type":"function","function":{"name":..,"arguments":..}}
        obj.optJSONObject("function")?.let { nested -> callOf(nested, byKey)?.let { return it } }
        val rawName = NAME_FIELDS.firstNotNullOfOrNull { field ->
            obj.opt(field)?.takeIf { it is String }?.let { it as String }
        } ?: return null
        val name = byKey[key(rawName)] ?: return null
        val argField = ARGUMENT_FIELDS.firstOrNull { obj.has(it) }
        val arguments = when (val value = argField?.let { obj.opt(it) }) {
            is JSONObject -> value.toString()
            is String -> argumentsOf(value)
            null -> {
                // Arguments written beside the name rather than nested: {"name":"web_search","query":"x"}
                val flat = JSONObject()
                obj.keys().asSequence()
                    .filter { it !in NAME_FIELDS && it != "type" && it != "id" }
                    .forEach { flat.put(it, obj.opt(it)) }
                flat.toString()
            }
            else -> "{}"
        }
        return ToolCall(callId = obj.optString("id").ifBlank { null }, name = name, argumentsJson = arguments)
    }

    /** Arguments given as a JSON-encoded string, or anything else that is not an object, as an object. */
    private fun argumentsOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "{}"
        runCatching { return JSONObject(trimmed).toString() }
        // A string that itself holds JSON: "arguments": "{\"q\":\"x\"}"
        runCatching {
            val unquoted = JSONArray("[$trimmed]").optString(0)
            return JSONObject(unquoted).toString()
        }
        return JSONObject().put("input", trimmed.trim('"')).toString()
    }

    /**
     * The balanced `{...}` or `[...]` starting at [start] (skipping leading whitespace), and the
     * index of its closing bracket. Strings are respected, so a brace inside a quoted value does not
     * end the object early.
     */
    private fun balancedAt(text: String, start: Int): Pair<String, Int>? {
        var s = start
        while (s < text.length && text[s].isWhitespace()) s++
        if (s >= text.length || (text[s] != '{' && text[s] != '[')) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (j in s until text.length) {
            val ch = text[j]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(s, j + 1) to j
                }
            }
        }
        return null
    }

    /** Names compared without case or separators: "Web-Search", "web search" and "web_search" match. */
    private fun key(name: String): String =
        name.trim().lowercase().replace(Regex("[\\s\\-.]+"), "_").trim('_', '`', '"', '\'')
}
