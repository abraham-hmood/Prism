package com.prism.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `org.json`'s API, implemented on kotlinx.serialization.
 *
 * WHY A COMPATIBILITY LAYER RATHER THAN @Serializable DATA CLASSES. Prism has 196 references to
 * `JSONObject`/`JSONArray` across 20 files -- the mesh gossip protocol, DNS records, the model
 * registry, the agentic wire format, cloud AI request and response bodies, Nebula. Converting
 * those to typed data classes would be a 20-file rewrite in which every file carries its own
 * chance of a subtly different wire format, and the wire format is the part that talks to other
 * Prism instances and to third-party HTTP APIs. A field that silently changes from absent to
 * `null`, or an integer that starts serializing as `1.0`, breaks a peer or an API and does it at
 * runtime, on someone else's machine.
 *
 * So the call sites do not change at all: they keep calling `getString`, `optJSONObject`, `put`.
 * Only the import moves, from `org.json` to here. What changes underneath is the parser, which
 * becomes kotlinx.serialization's -- available on every platform, where `org.json` ships inside
 * `android.jar` and exists on desktop only if you add a separate library.
 *
 * DELIBERATE DIFFERENCES FROM org.json, all in the direction of being stricter or safer:
 *
 *   - `getX` throws [JSONException] on a missing or wrongly-typed key, as org.json does. `optX`
 *     returns the fallback. This is the distinction the call sites already rely on.
 *   - Key order is preserved (`LinkedHashMap`), which org.json does not guarantee. Prism's mesh
 *     code compares serialized payloads in a couple of places, and stable ordering makes that
 *     deterministic instead of accidentally working.
 *   - `toString()` produces compact JSON with no spaces, matching org.json closely enough that
 *     existing stored payloads and HTTP bodies are unchanged.
 */
class JSONException(message: String) : RuntimeException(message)

/** The sentinel for an explicit JSON null, as `org.json.JSONObject.NULL`. */
object JSONNull {
    override fun toString(): String = "null"
}

// ── Conversion between the mutable tree used here and kotlinx's immutable one ────────────────

private fun JsonElement.unwrap(): Any? = when (this) {
    is JsonNull -> JSONNull
    is JsonObject -> JSONObject(LinkedHashMap(mapValues { it.value.unwrap() }))
    is JsonArray -> JSONArray(map { it.unwrap() }.toMutableList())
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    }
}

private fun Any?.wrap(): JsonElement = when (this) {
    null, JSONNull -> JsonNull
    is JSONObject -> JsonObject(entries().associate { it to values(it).wrap() })
    is JSONArray -> JsonArray(toList().map { it.wrap() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.wrap() })
    is Collection<*> -> JsonArray(map { it.wrap() })
    else -> JsonPrimitive(toString())
}

private val PARSER = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // Prism's own payloads never contain literal NaN or Infinity, but a third-party API might,
    // and rejecting the whole response over one field is worse than accepting it.
    allowSpecialFloatingPointValues = true
}

private fun parse(text: String): Any? =
    try {
        PARSER.parseToJsonElement(text.trim()).unwrap()
    } catch (e: Exception) {
        throw JSONException("Malformed JSON: ${e.message}")
    }

// ── JSONObject ───────────────────────────────────────────────────────────────────────────────

class JSONObject internal constructor(private val map: LinkedHashMap<String, Any?>) {

    constructor() : this(LinkedHashMap())

    constructor(text: String) : this(
        (parse(text) as? JSONObject)?.map ?: throw JSONException("Not a JSON object")
    )

    constructor(source: Map<*, *>) : this(
        LinkedHashMap<String, Any?>().apply {
            source.forEach { (k, v) -> put(k.toString(), normalize(v)) }
        }
    )

    internal fun entries(): Set<String> = map.keys
    internal fun values(key: String): Any? = map[key]

    fun length(): Int = map.size
    fun has(key: String): Boolean = map.containsKey(key)
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray(map.keys.toMutableList<Any?>())
    fun remove(key: String): Any? = map.remove(key)

    fun put(key: String, value: Any?): JSONObject {
        map[key] = normalize(value)
        return this
    }

    /** `putOpt` skips nulls, as org.json's does. */
    fun putOpt(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    fun opt(key: String): Any? = map[key]

    fun get(key: String): Any =
        map[key] ?: throw JSONException("No value for \"$key\"")

    fun getString(key: String): String = when (val v = get(key)) {
        is String -> v
        is JSONObject, is JSONArray -> throw JSONException("\"$key\" is not a string")
        else -> v.toString()
    }

    fun getInt(key: String): Int = number(key, get(key)).toInt()
    fun getLong(key: String): Long = number(key, get(key)).toLong()
    fun getDouble(key: String): Double = number(key, get(key)).toDouble()

    fun getBoolean(key: String): Boolean = when (val v = get(key)) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: throw JSONException("\"$key\" is not a boolean")
        else -> throw JSONException("\"$key\" is not a boolean")
    }

    fun getJSONObject(key: String): JSONObject =
        get(key) as? JSONObject ?: throw JSONException("\"$key\" is not an object")

    fun getJSONArray(key: String): JSONArray =
        get(key) as? JSONArray ?: throw JSONException("\"$key\" is not an array")

    fun optString(key: String, fallback: String = ""): String =
        when (val v = map[key]) {
            null, JSONNull -> fallback
            is String -> v
            is JSONObject, is JSONArray -> fallback
            else -> v.toString()
        }

    fun optInt(key: String, fallback: Int = 0): Int = optNumber(key)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = optNumber(key)?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double = 0.0): Double = optNumber(key)?.toDouble() ?: fallback

    fun optBoolean(key: String, fallback: Boolean = false): Boolean =
        when (val v = map[key]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: fallback
            else -> fallback
        }

    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    /** `isNull` is true for both an absent key and an explicit JSON null, as in org.json. */
    fun isNull(key: String): Boolean = map[key].let { it == null || it === JSONNull }

    override fun toString(): String = PARSER.encodeToString(JsonElement.serializer(), this.wrap())

    /** Pretty-printed. The argument is accepted for source compatibility and otherwise ignored. */
    fun toString(indentSpaces: Int): String =
        PRETTY.encodeToString(JsonElement.serializer(), this.wrap())

    private fun optNumber(key: String): Number? = when (val v = map[key]) {
        is Number -> v
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
        else -> null
    }

    private fun number(key: String, v: Any): Number = when (v) {
        is Number -> v
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
            ?: throw JSONException("\"$key\" is not a number")
        else -> throw JSONException("\"$key\" is not a number")
    }

    companion object {
        /** org.json spells the null sentinel `JSONObject.NULL`; keep that spelling working. */
        @JvmField
        val NULL: Any = JSONNull

        private val PRETTY = Json { prettyPrint = true; isLenient = true }

        internal fun normalize(value: Any?): Any? = when (value) {
            null -> JSONNull
            is Map<*, *> -> JSONObject(value)
            is Collection<*> -> JSONArray(value.map { normalize(it) }.toMutableList())
            is Array<*> -> JSONArray(value.map { normalize(it) }.toMutableList())
            else -> value
        }
    }
}

// ── JSONArray ────────────────────────────────────────────────────────────────────────────────

class JSONArray internal constructor(private val list: MutableList<Any?>) {

    constructor() : this(mutableListOf())

    constructor(text: String) : this(
        (parse(text) as? JSONArray)?.list ?: throw JSONException("Not a JSON array")
    )

    constructor(source: Collection<*>) : this(
        source.map { JSONObject.normalize(it) }.toMutableList()
    )

    internal fun toList(): List<Any?> = list

    fun length(): Int = list.size

    fun put(value: Any?): JSONArray {
        list.add(JSONObject.normalize(value))
        return this
    }

    fun put(index: Int, value: Any?): JSONArray {
        while (list.size <= index) list.add(JSONNull)
        list[index] = JSONObject.normalize(value)
        return this
    }

    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

    fun opt(index: Int): Any? = list.getOrNull(index)

    fun get(index: Int): Any =
        list.getOrNull(index) ?: throw JSONException("No value at $index")

    fun getString(index: Int): String = when (val v = get(index)) {
        is String -> v
        is JSONObject, is JSONArray -> throw JSONException("Value at $index is not a string")
        else -> v.toString()
    }

    fun getInt(index: Int): Int = (get(index) as? Number)?.toInt()
        ?: throw JSONException("Value at $index is not a number")

    fun getLong(index: Int): Long = (get(index) as? Number)?.toLong()
        ?: throw JSONException("Value at $index is not a number")

    fun getDouble(index: Int): Double = (get(index) as? Number)?.toDouble()
        ?: throw JSONException("Value at $index is not a number")

    fun getBoolean(index: Int): Boolean = get(index) as? Boolean
        ?: throw JSONException("Value at $index is not a boolean")

    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject
        ?: throw JSONException("Value at $index is not an object")

    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray
        ?: throw JSONException("Value at $index is not an array")

    fun optString(index: Int, fallback: String = ""): String = when (val v = list.getOrNull(index)) {
        null, JSONNull -> fallback
        is String -> v
        is JSONObject, is JSONArray -> fallback
        else -> v.toString()
    }

    fun optInt(index: Int, fallback: Int = 0): Int =
        (list.getOrNull(index) as? Number)?.toInt() ?: fallback

    fun optLong(index: Int, fallback: Long = 0L): Long =
        (list.getOrNull(index) as? Number)?.toLong() ?: fallback

    fun optDouble(index: Int, fallback: Double = 0.0): Double =
        (list.getOrNull(index) as? Number)?.toDouble() ?: fallback

    fun optBoolean(index: Int, fallback: Boolean = false): Boolean =
        list.getOrNull(index) as? Boolean ?: fallback

    fun optJSONObject(index: Int): JSONObject? = list.getOrNull(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = list.getOrNull(index) as? JSONArray

    fun isNull(index: Int): Boolean = list.getOrNull(index).let { it == null || it === JSONNull }

    override fun toString(): String = PARSER.encodeToString(JsonElement.serializer(), this.wrap())
}

/** `JSONTokener(text).nextValue()`, the third entry point Prism uses. */
class JSONTokener(private val text: String) {
    fun nextValue(): Any? = parse(text)
}
