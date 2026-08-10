package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONException
import com.prism.core.json.JSONObject
import com.prism.core.json.JSONTokener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the JSON compatibility layer against `org.json`'s behaviour.
 *
 * This is the one replacement in the port where the risk is not "does it compile" but "does it
 * produce the same bytes". These objects are the mesh gossip protocol, DNS records, the P2P model
 * registry, the agentic tool-calling wire format and cloud AI request bodies. A difference shows
 * up as a peer that will not handshake or an HTTP API that 400s -- on someone else's machine,
 * with no stack trace pointing here.
 *
 * So these tests assert the *exact* output string wherever a wire format is involved, and pin the
 * get-vs-opt distinction, which is what all 196 converted call sites are built on.
 */
class JsonTest {

    // ── Output format ────────────────────────────────────────────────────────────────────────

    /** Compact, no spaces. org.json's `toString()` is the same, and peers parse this. */
    @Test
    fun `objects serialize compactly`() {
        val o = JSONObject().put("a", 1).put("b", "x").put("c", true)
        assertEquals("""{"a":1,"b":"x","c":true}""", o.toString())
    }

    /**
     * Insertion order is preserved.
     *
     * org.json happens to do this too (it is a LinkedHashMap inside), and a couple of places in
     * the mesh code compare serialized payloads, which only works if ordering is deterministic.
     */
    @Test
    fun `key order is insertion order`() {
        val o = JSONObject().put("z", 1).put("a", 2).put("m", 3)
        assertEquals("""{"z":1,"a":2,"m":3}""", o.toString())
    }

    /** Integers must not acquire a decimal point on the way out. */
    @Test
    fun `integers stay integers`() {
        assertEquals("""{"n":5}""", JSONObject().put("n", 5).toString())
        assertEquals("""{"n":5}""", JSONObject("""{"n":5}""").toString())
        assertEquals("""{"n":5.5}""", JSONObject().put("n", 5.5).toString())
        assertEquals(5, JSONObject("""{"n":5}""").getInt("n"))
    }

    /** Round-tripping a payload must not perturb it. */
    @Test
    fun `nested payloads round-trip byte-for-byte`() {
        val wire = """{"type":"gossip","peers":[{"ip":"10.0.0.1","port":8080}],"ttl":3}"""
        assertEquals(wire, JSONObject(wire).toString())

        val arr = """[{"a":1},{"a":2}]"""
        assertEquals(arr, JSONArray(arr).toString())
    }

    /** Strings containing JSON metacharacters must be escaped, not smuggled through. */
    @Test
    fun `strings are escaped`() {
        val o = JSONObject().put("s", """he said "hi"\ then left""")
        // Re-parsing must yield the original, which is the property that actually matters.
        assertEquals("""he said "hi"\ then left""", JSONObject(o.toString()).getString("s"))
    }

    // ── get vs opt ───────────────────────────────────────────────────────────────────────────

    /** `getX` throws on a missing key. Every call site distinguishes this from `optX`. */
    @Test
    fun `get throws on missing keys`() {
        val o = JSONObject("""{"a":1}""")
        assertFailsWith<JSONException> { o.getString("nope") }
        assertFailsWith<JSONException> { o.getInt("nope") }
        assertFailsWith<JSONException> { o.getJSONObject("nope") }
        assertFailsWith<JSONException> { o.getJSONArray("nope") }
    }

    /** `optX` returns the fallback instead. */
    @Test
    fun `opt returns fallbacks`() {
        val o = JSONObject("""{"a":1}""")
        assertEquals("", o.optString("nope"))
        assertEquals("dflt", o.optString("nope", "dflt"))
        assertEquals(0, o.optInt("nope"))
        assertEquals(7, o.optInt("nope", 7))
        assertEquals(7L, o.optLong("nope", 7L))
        assertFalse(o.optBoolean("nope"))
        assertTrue(o.optBoolean("nope", true))
        assertNull(o.optJSONObject("nope"))
        assertNull(o.optJSONArray("nope"))
    }

    /** A wrongly-typed key is a miss for `opt`, not a crash. */
    @Test
    fun `opt tolerates wrong types`() {
        val o = JSONObject("""{"obj":{"k":1},"arr":[1],"s":"text"}""")
        assertNull(o.optJSONArray("obj"))
        assertNull(o.optJSONObject("arr"))
        assertEquals(0, o.optInt("s"))
        // ...but a numeric string still reads as a number, as org.json does.
        assertEquals(42, JSONObject("""{"n":"42"}""").optInt("n"))
    }

    // ── Null handling ────────────────────────────────────────────────────────────────────────

    /**
     * `isNull` is true for an absent key AND an explicit JSON null.
     *
     * DiscoveryEngine relies on exactly this after the `optString(key, null)` call site was
     * rewritten, so it is load-bearing rather than incidental.
     */
    @Test
    fun `isNull covers absent and explicit null`() {
        val o = JSONObject("""{"present":"x","explicit":null}""")
        assertFalse(o.isNull("present"))
        assertTrue(o.isNull("explicit"))
        assertTrue(o.isNull("absent"))
        assertEquals("", o.optString("explicit"))
    }

    // ── Structure ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `objects support membership and removal`() {
        val o = JSONObject().put("a", 1).put("b", 2)
        assertTrue(o.has("a"))
        assertEquals(2, o.length())
        o.remove("a")
        assertFalse(o.has("a"))
        assertEquals(1, o.length())
        assertEquals(listOf("b"), o.keys().asSequence().toList())
    }

    @Test
    fun `arrays index and grow`() {
        val a = JSONArray().put("x").put(2).put(true)
        assertEquals(3, a.length())
        assertEquals("x", a.getString(0))
        assertEquals(2, a.getInt(1))
        assertTrue(a.getBoolean(2))
        assertEquals("""["x",2,true]""", a.toString())

        // put(index, value) past the end pads with nulls, as org.json does.
        a.put(5, "far")
        assertEquals(6, a.length())
        assertTrue(a.isNull(4))
        assertEquals("far", a.getString(5))
    }

    @Test
    fun `arrays of objects are navigable`() {
        val a = JSONArray("""[{"n":"a"},{"n":"b"}]""")
        assertEquals("a", a.getJSONObject(0).getString("n"))
        assertEquals("b", a.getJSONObject(1).getString("n"))
        assertNull(a.optJSONObject(9))
    }

    /** Kotlin maps and collections handed to `put` become real JSON structures. */
    @Test
    fun `maps and collections are converted`() {
        val o = JSONObject().put("m", mapOf("k" to 1)).put("l", listOf(1, 2))
        assertEquals("""{"m":{"k":1},"l":[1,2]}""", o.toString())
        assertEquals(1, o.getJSONObject("m").getInt("k"))
        assertEquals(2, o.getJSONArray("l").getInt(1))
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `malformed input throws rather than returning empty`() {
        assertFailsWith<JSONException> { JSONObject("not json") }
        assertFailsWith<JSONException> { JSONObject("""{"a":""") }
        // An array is not an object, and vice versa.
        assertFailsWith<JSONException> { JSONObject("""[1,2]""") }
        assertFailsWith<JSONException> { JSONArray("""{"a":1}""") }
    }

    /** Leading and trailing whitespace is tolerated -- HTTP bodies routinely have it. */
    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(1, JSONObject("  \n {\"a\":1}\n ").getInt("a"))
    }

    @Test
    fun `tokener yields the parsed value`() {
        assertTrue(JSONTokener("""{"a":1}""").nextValue() is JSONObject)
        assertTrue(JSONTokener("""[1]""").nextValue() is JSONArray)
    }

    /** Deeply nested structures, of the shape the agentic tool schema actually uses. */
    @Test
    fun `agentic tool schema shape survives`() {
        val schema = """{"type":"object","properties":{"city":{"type":"string",""" +
            """"description":"Which city"}},"required":["city"]}"""
        val o = JSONObject(schema)
        assertEquals("object", o.getString("type"))
        assertEquals("Which city", o.getJSONObject("properties").getJSONObject("city").getString("description"))
        assertEquals("city", o.getJSONArray("required").getString(0))
        assertEquals(schema, o.toString())
    }
}
