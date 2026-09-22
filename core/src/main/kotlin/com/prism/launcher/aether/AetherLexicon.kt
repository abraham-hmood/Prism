package com.prism.launcher.aether

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * Words as units, instead of letters.
 *
 * Kotlin port of AetherCortex's `execution/lexicon.py`.
 *
 * ## Why Broca should not be spelling
 *
 * The character band ([AetherTextCoding]) is a flashcard code: one neuron per printable ASCII
 * glyph. That was right for labelling a picture with its filename, and dialogue inherited it --
 * under which saying "i see it" means independently winning EIGHT sequential 95-way competitions
 * with nothing anywhere in the connectome representing the word "see". The measured failure
 * signature was exactly that: right letters, wrong positions, dropped and doubled -- `i  ee` for
 * "i see it", `bes` for "bye". A network that knows roughly what it wants to say and has no unit
 * large enough to hold it.
 *
 * No human produces speech that way. In Levelt's model and in GODIVA, production runs lemma ->
 * phonological word -> syllable frame -> articulation, with a mental syllabary of pre-compiled
 * motor programs; the syllable, not the letter, is the retrieval unit. Letters are an artefact of
 * writing, and writing is a late skill layered on a speech system that was already complete.
 *
 * So Broca gets a lexical band: one neuron per known word. "i see it" becomes three units, "hi"
 * becomes one. Sequence length collapses by roughly a factor of three and every unit is a real
 * lexical item.
 *
 * ## The band is appended, never inserted
 *
 * Every existing band keeps its indices, so a connectome saved before the lexicon existed still
 * restores block-for-block.
 */
class AetherLexicon private constructor(val words: List<String>) {

    private val index: Map<String, Int> = words.withIndex().associate { (i, w) -> w to i }

    val size: Int get() = words.size

    /**
     * Known words only.
     *
     * An unknown word is DROPPED rather than mapped to a catch-all: there is no neuron that means
     * "some word I do not know", and inventing one would teach her to say it.
     */
    fun encode(text: String): List<Int> =
        wordsIn(text).mapNotNull { index[it] }

    fun decode(indices: Iterable<Int>): String =
        indices.filter { it > 0 && it < words.size }.joinToString(" ") { words[it] }

    fun contains(word: String): Boolean = word in index

    /** Whether every word of [text] is representable. See `AetherDialogue.fitsWindow`. */
    fun covers(text: String): Boolean {
        val spoken = wordsIn(text)
        return spoken.isNotEmpty() && encode(text).size == spoken.size
    }

    // ── Persistence ────────────────────────────────────────────────────────
    //
    // Saved WITH the connectome and never rebuilt from a corpus at load time. Neuron j means
    // whichever word sat at index j during training; rebuilding the order from a different corpus
    // would silently reassign every word to a different neuron and make a trained Broca speak
    // gibberish -- the same class of bug as reordering a phoneme list.

    fun save(modelDir: File): File {
        modelDir.mkdirs()
        val target = pathIn(modelDir)
        val array = JSONArray()
        words.forEach { array.put(it) }
        target.writeText(JSONObject().put("words", array).toString())
        return target
    }

    companion object {

        /**
         * Index 0 is not a word.
         *
         * It is "nothing is being said in this slot", which a rate-coded band needs as a real
         * class -- the silence between words and the padding after a turn are both this.
         */
        const val SILENCE = "<sil>"

        private val WORD = Regex("[a-z']+")

        fun wordsIn(text: String): List<String> =
            WORD.findAll(text.lowercase()).map { it.value }.toList()

        fun of(words: Iterable<String>): AetherLexicon {
            val ordered = listOf(SILENCE) +
                words.filter { it.isNotBlank() && it != SILENCE }.distinct().sorted()
            require(ordered.size <= AetherDialogueConfig.WORD_BAND_WIDTH) {
                "${ordered.size} words do not fit a ${AetherDialogueConfig.WORD_BAND_WIDTH}-neuron band"
            }
            return AetherLexicon(ordered)
        }

        /**
         * The most FREQUENT words that fit the band, not an arbitrary alphabetical slice.
         *
         * A hand-written corpus has thirty-five words and everything fits. A real transcript
         * corpus has thirteen thousand against a band of a hundred and twenty-eight, so a choice
         * has to be made, and frequency is the one with an answer behind it: vocabulary
         * acquisition is strongly frequency-ordered, children learn the commonest words first,
         * and the commonest words recur often enough to be learnable from a handful of exposures.
         * Taking the alphabetical head instead would hand her 'a' through 'b' and call it a
         * vocabulary.
         */
        fun fromConversations(
            conversations: List<AetherDialogue.Conversation>,
            limit: Int = AetherDialogueConfig.WORD_BAND_WIDTH,
        ): AetherLexicon {
            val counts = HashMap<String, Int>()
            conversations.forEach { conversation ->
                conversation.turns.forEach { turn ->
                    wordsIn(turn.text).forEach { counts[it] = (counts[it] ?: 0) + 1 }
                }
            }
            // -1 for SILENCE, which always occupies index 0.
            val keep = counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(maxOf(0, limit - 1))
                .map { it.key }

            if (counts.size > keep.size) {
                val total = counts.values.sum().coerceAtLeast(1)
                val covered = keep.sumOf { counts[it] ?: 0 }.toDouble() / total
                AetherLog.info(
                    "Aether",
                    "Lexicon: ${counts.size} distinct words, keeping the ${keep.size} most " +
                        "frequent (${"%.1f".format(covered * 100)}% of all tokens)"
                )
            }
            return of(keep)
        }

        fun pathIn(modelDir: File): File = File(modelDir, "lexicon.json")

        fun exists(modelDir: File): Boolean = pathIn(modelDir).isFile

        fun load(modelDir: File): AetherLexicon? = runCatching {
            val json = JSONObject(pathIn(modelDir).readText())
            val array = json.optJSONArray("words") ?: return null
            // Order preserved EXACTLY as saved -- see the persistence note above.
            AetherLexicon((0 until array.length()).map { array.optString(it) })
        }.getOrNull()
    }
}
