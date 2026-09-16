package com.prism.launcher.cakechat

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the Kotlin port against what CakeChat's Python actually does.
 *
 * ## Why a recorded fixture rather than hand-written expectations
 *
 * `core/src/test/resources/cakechat_reference.json` is produced by `make_fixture.py` running the
 * REAL upstream code -- `get_tokens_sequence`, `transform_contexts_to_token_ids` and
 * `TokenSampler` -- against the same metadata the app reads. Expectations written by hand would
 * only encode what the port's author believed Python does, which is precisely the thing in doubt.
 *
 * ## Why these three things specifically
 *
 * They are the parts that fail SILENTLY. A model that will not load raises and gets reported; a
 * tokeniser that splits accented words into characters, a context matrix padded from the wrong end,
 * or a repetition penalty applied per use instead of per distinct token all produce a fluent reply
 * to something the user did not say, with nothing logged anywhere. The TFLite plumbing around them
 * is mechanical by comparison and fails loudly when it is wrong.
 */
class CakeChatPortTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("cakechat_reference.json")
        checkNotNull(stream) { "cakechat_reference.json is missing; run make_fixture.py" }
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun vocabulary(): CakeChatVocabulary {
        // Built through the same parser the app uses, from the fixture's own copy of the metadata,
        // so the test cannot pass by disagreeing with production about how the file is read.
        val meta = JSONObject(
            """
            {"index_to_token": ${fixture.optJSONObject("index_to_token")},
             "input_seq_len": ${fixture.optInt("input_seq_len")},
             "input_context_size": ${fixture.optInt("input_context_size")},
             "pad_token_id": ${fixture.optInt("pad_token_id")},
             "unk_token_id": ${fixture.optInt("unk_token_id")}}
            """.trimIndent()
        )
        return CakeChatVocabulary.fromJson(meta)
    }

    @Test
    fun `tokenizer matches upstream`() {
        val cases = fixture.optJSONArray("tokenize") ?: error("no tokenize cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.optJSONObject(i) ?: continue
            val text = case.optString("text")
            val expected = (case.optJSONArray("tokens") ?: JSONArray()).let { array ->
                (0 until array.length()).map { array.optString(it) }
            }
            assertEquals(expected, CakeChatVocabulary.tokenize(text), "tokenizing <$text>")
        }
    }

    @Test
    fun `context encoding matches upstream`() {
        val vocabulary = vocabulary()
        val cases = fixture.optJSONArray("contexts") ?: error("no context cases")
        for (i in 0 until cases.length()) {
            val case = cases.optJSONObject(i) ?: continue
            val dialogArray = case.optJSONArray("dialog") ?: JSONArray()
            val dialog = (0 until dialogArray.length()).map { dialogArray.optString(it) }

            val expected = case.optJSONArray("matrix") ?: error("no matrix")
            val actual = vocabulary.encodeContext(dialog)

            assertEquals(expected.length(), actual.size, "context rows for $dialog")
            for (row in 0 until expected.length()) {
                val expectedRow = expected.optJSONArray(row) ?: error("row $row")
                val expectedIds = (0 until expectedRow.length()).map { expectedRow.optInt(it) }
                assertEquals(expectedIds, actual[row].toList(), "row $row of $dialog")
            }
        }
    }

    @Test
    fun `sampler matches upstream`() {
        val banned = fixture.optJSONArray("banned_token_ids").toIntSet()
        val nonPenalizable = fixture.optJSONArray("non_penalizable_token_ids").toIntSet()
        val coefficient = fixture.optDouble("repetition_penalize_coefficient", 10.0)

        val cases = fixture.optJSONArray("sampler") ?: error("no sampler cases")
        for (i in 0 until cases.length()) {
            val case = cases.optJSONObject(i) ?: continue
            val temperature = case.optDouble("temperature", 1.0)

            // One sampler across the whole case, because the penalty depends on what it has already
            // emitted -- resetting per step would test nothing about repetition at all.
            val sampler = CakeChatSampler(banned, nonPenalizable, coefficient) { 0.0 }
            val steps = case.optJSONArray("steps") ?: continue
            for (step in 0 until steps.length()) {
                val record = steps.optJSONObject(step) ?: continue
                val probsArray = record.optJSONArray("probs") ?: continue
                val probs = FloatArray(probsArray.length()) { probsArray.optDouble(it, 0.0).toFloat() }

                assertEquals(
                    record.optInt("expected"),
                    sampler.sample(probs, temperature, greedy = true),
                    "temperature=$temperature step=$step",
                )
            }
        }
    }

    @Test
    fun `banned tokens are never chosen`() {
        val banned = fixture.optJSONArray("banned_token_ids").toIntSet()
        if (banned.isEmpty()) return

        val size = fixture.optInt("vocab_size", 0)
        assertTrue(size > 0)
        // A distribution that would otherwise pick a banned token every time.
        val probs = FloatArray(size) { 1e-6f }
        for (id in banned) probs[id] = 1f

        val sampler = CakeChatSampler(banned, emptySet(), 10.0) { 0.0 }
        val chosen = sampler.sample(probs, 1.0, greedy = true)
        assertTrue(chosen !in banned, "sampler chose banned token $chosen")
    }

    private fun JSONArray?.toIntSet(): Set<Int> {
        if (this == null) return emptySet()
        return (0 until length()).map { optInt(it) }.toSet()
    }
}
