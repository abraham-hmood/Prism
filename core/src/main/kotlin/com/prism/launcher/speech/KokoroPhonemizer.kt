package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File

/**
 * Turns English text into the IPA Kokoro expects.
 *
 * ## Why a lexicon and not rules
 *
 * English spelling does not determine pronunciation -- "read", "lead", "tear" and "wound" each have
 * two, and no rule set gets "colonel" or "choir" right. Kokoro's own reference implementation uses
 * misaki, which is a dictionary first and rules second, and this uses misaki's published lexicon for
 * exactly that reason: it is the same table the model was trained against, so the phonemes it
 * receives are the phonemes it learned.
 *
 * ## What happens to a word that is not in it
 *
 * Three fallbacks, in order, before giving up:
 *
 *  1. lowercase, since the lexicon is lowercase and "Prism" is the same word as "prism";
 *  2. a small set of regular suffixes -- plural, past, progressive, possessive -- stripped and the
 *     stem looked up, with the suffix's own phonemes appended. This is what makes a 100k-word
 *     lexicon cover far more than 100k words;
 *  3. spelled out letter by letter, which is right for acronyms and wrong but intelligible for
 *     anything else.
 *
 * Silently dropping the word was the alternative, and it is worse: a sentence missing its verb is
 * harder to understand than one with a word spelled out.
 */
class KokoroPhonemizer private constructor(private val lexicon: Map<String, String>) {

    /**
     * Phonemises a sentence.
     *
     * Punctuation is kept, not stripped: Kokoro's vocabulary contains commas, full stops and
     * question marks, and they are what give a sentence its prosody. Removing them produces a flat
     * monotone reading of the right words.
     */
    fun phonemize(text: String): String {
        val out = StringBuilder()
        var index = 0

        while (index < text.length) {
            val ch = text[index]
            when {
                ch.isLetter() || ch == '\'' -> {
                    val start = index
                    while (index < text.length && (text[index].isLetter() || text[index] == '\'')) index++
                    out.append(wordToIpa(text.substring(start, index)))
                }

                ch.isDigit() -> {
                    val start = index
                    while (index < text.length && text[index].isDigit()) index++
                    out.append(numberToIpa(text.substring(start, index)))
                }

                ch.isWhitespace() -> {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    index++
                }

                // Carried through as themselves; the vocabulary has them.
                ch in ",.!?;:" -> {
                    out.append(ch)
                    index++
                }

                else -> index++
            }
            if (index < text.length && out.isNotEmpty() && out.last() != ' ') {
                val next = text.getOrNull(index)
                if (next != null && (next.isLetter() || next.isDigit())) out.append(' ')
            }
        }

        return out.toString().trim()
    }

    private fun wordToIpa(raw: String): String {
        val word = raw.trim('\'')
        if (word.isEmpty()) return ""

        lexicon[word]?.let { return it }
        lexicon[word.lowercase()]?.let { return it }

        val lower = word.lowercase()

        for ((suffix, phonemes) in SUFFIXES) {
            if (!lower.endsWith(suffix) || lower.length <= suffix.length + 1) continue
            val stem = lower.dropLast(suffix.length)
            lexicon[stem]?.let { return it + phonemes }
            // "tries" -> "try", "hoped" -> "hope": the two spelling changes regular suffixes make.
            lexicon[stem.dropLast(1) + "y"]?.let { if (stem.endsWith("i")) return it + phonemes }
            lexicon[stem + "e"]?.let { return it + phonemes }
        }

        return spell(lower)
    }

    /** Letter names, for acronyms and for anything the lexicon and its suffix rules both missed. */
    private fun spell(word: String): String =
        word.mapNotNull { LETTERS[it] }.joinToString(" ")

    /**
     * Reads a number as digits rather than as a quantity.
     *
     * "2024" becomes two-zero-two-four, not "two thousand and twenty four". Wrong for prose and
     * right for the version numbers, ports and identifiers that dominate what Prism actually says;
     * full number expansion is a language problem of its own and is not attempted here.
     */
    private fun numberToIpa(digits: String): String =
        digits.mapNotNull { DIGITS[it] }.joinToString(" ")

    companion object {

        private const val TAG = "PrismSpeech"

        /** Regular English suffixes and the phonemes they add, longest first so "ies" beats "s". */
        private val SUFFIXES = listOf(
            "'s" to "z",
            "ing" to "ɪŋ",
            "ies" to "iz",
            "ed" to "d",
            "es" to "ɪz",
            "s" to "z",
        )

        private val LETTERS = mapOf(
            'a' to "ˈA", 'b' to "bˈi", 'c' to "sˈi", 'd' to "dˈi", 'e' to "ˈi", 'f' to "ˈɛf",
            'g' to "ʤˈi", 'h' to "ˈAʧ", 'i' to "ˈI", 'j' to "ʤˈA", 'k' to "kˈA", 'l' to "ˈɛl",
            'm' to "ˈɛm", 'n' to "ˈɛn", 'o' to "ˈO", 'p' to "pˈi", 'q' to "kjˈu", 'r' to "ˈɑɹ",
            's' to "ˈɛs", 't' to "tˈi", 'u' to "jˈu", 'v' to "vˈi", 'w' to "dˈʌbəljˌu",
            'x' to "ˈɛks", 'y' to "wˈI", 'z' to "zˈi",
        )

        private val DIGITS = mapOf(
            '0' to "zˈiɹO", '1' to "wˈʌn", '2' to "tˈu", '3' to "θɹˈi", '4' to "fˈɔɹ",
            '5' to "fˈIv", '6' to "sˈɪks", '7' to "sˈɛvən", '8' to "ˈAt", '9' to "nˈIn",
        )

        /**
         * Loads a misaki lexicon.
         *
         * Entries are either a bare IPA string or an object keyed by part of speech; the latter is
         * flattened to whichever reading the file lists first, because choosing between "READ the
         * book" and "I have READ it" needs a tagger this does not have.
         */
        fun load(file: File): KokoroPhonemizer? = runCatching {
            val root = JSONObject(file.readText())
            val lexicon = HashMap<String, String>(root.length())

            for (word in root.keys()) {
                when (val value = root.get(word)) {
                    is String -> lexicon[word] = value
                    is JSONObject -> {
                        val keys = value.keys()
                        if (keys.hasNext()) {
                            val first = value.opt(keys.next())
                            if (first is String) lexicon[word] = first
                        }
                    }
                }
            }

            PrismPlatform.log.info(TAG, "Phoneme lexicon: ${lexicon.size} words")
            KokoroPhonemizer(lexicon)
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not read the phoneme lexicon: ${it.message}")
            null
        }
    }
}
