package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File

/**
 * Kokoro's phoneme vocabulary, read from the `tokenizer.json` published with the model.
 *
 * ## What this maps
 *
 * Not words, and not letters: IPA symbols. `ˈ`, `ɹ`, `ə`, `ŋ` and about 170 others each have an id,
 * and the model takes a sequence of those ids. Text never reaches it -- see [KokoroPhonemizer] for
 * the step that turns words into these symbols.
 *
 * Reading it from the file rather than hardcoding it matters: the vocabulary belongs to the export,
 * and a future Kokoro build with two more symbols would silently shift every id if the table lived
 * in this source instead.
 */
class KokoroTokenizer private constructor(private val vocab: Map<String, Int>) {

    /** The padding symbol Kokoro brackets every utterance with. */
    private val pad: Int = vocab["$"] ?: 0

    /**
     * Turns a phoneme string into model input ids, bracketed with padding.
     *
     * Walks longest-match-first because IPA is not one character per symbol -- `ɔː` and `tʃ` are
     * single entries, and matching character by character would split them into two symbols that
     * mean something else.
     */
    fun encode(phonemes: String): IntArray {
        val ids = ArrayList<Int>(phonemes.length + 2)
        var index = 0

        while (index < phonemes.length) {
            var matched = false
            // Three is the longest entry in the published vocabulary; trying longer costs nothing
            // but is pointless, and trying shorter first would break digraphs.
            for (length in minOf(3, phonemes.length - index) downTo 1) {
                val candidate = phonemes.substring(index, index + length)
                val id = vocab[candidate]
                if (id != null) {
                    ids.add(id)
                    index += length
                    matched = true
                    break
                }
            }
            if (!matched) {
                // Dropped rather than substituted. An unknown symbol has no sound to stand in for,
                // and inserting a wrong one is worse than a gap the listener will not hear.
                index++
            }
        }

        return IntArray(ids.size + 2).also { out ->
            out[0] = pad
            for (i in ids.indices) out[i + 1] = ids[i]
            out[out.size - 1] = pad
        }
    }

    companion object {

        private const val TAG = "PrismSpeech"

        /**
         * Parses the Hugging Face tokenizer file.
         *
         * The table lives under `model.vocab` in the standard layout, but a bare `vocab` object is
         * also accepted because not every export writes the wrapper.
         */
        fun load(file: File): KokoroTokenizer? = runCatching {
            val root = JSONObject(file.readText())
            val table = root.optJSONObject("model")?.optJSONObject("vocab")
                ?: root.optJSONObject("vocab")
                ?: return null

            val vocab = HashMap<String, Int>(table.length())
            for (symbol in table.keys()) vocab[symbol] = table.getInt(symbol)

            PrismPlatform.log.info(TAG, "Kokoro vocabulary: ${vocab.size} symbols")
            KokoroTokenizer(vocab)
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not read the Kokoro tokenizer: ${it.message}")
            null
        }
    }
}
