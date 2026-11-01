package com.prism.launcher.modelcraft

import java.text.Normalizer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Text to phonemes.
 *
 * ## Why rules, and what they are worth
 *
 * A neural grapheme-to-phoneme model needs a pronunciation dictionary to train on and a tagger to
 * disambiguate "read" from "read"; neither is in this build. What is here is the other honest option:
 * an ordered rule set, longest match first, with the digraphs and vowel teams that English spelling
 * actually distinguishes. It gets "sh", "th", "ee", "oa", "igh" and the rest right, it inserts a
 * primary stress before each word's first vowel so the duration model has something to lengthen, and
 * it decompresses numbers into words. It is not misaki, and the artifact says so.
 *
 * ## Why the output is tokens and not a string
 *
 * The trainer aligns tokens to frames. A token is the thing a duration is predicted for, so the
 * front end has to say where one ends -- "tʃ" is one consonant, not two -- and a list of symbols is
 * that answer while a string is not.
 *
 * ## Other languages
 *
 * For a language this cannot phonemise, letters are mapped through the same IPA table one at a time
 * instead of through English digraphs. That is a pronunciation approximation, not a model of the
 * language; the language embedding is what lets a trained voice learn the dataset's own mapping, and
 * a dataset of Chinese text would need a different front end and is reported as such by the trainer.
 */
object PrismPhonemes {

    const val PAD = "_"
    const val SPACE = " "
    const val STRESS = "ˈ"

    /**
     * The fixed symbol set.
     *
     * Every symbol the rules can emit is here, so the vocabulary is the same for every model and a
     * weights file from one run can be read by another. Index 0 is padding and doubles as "unknown".
     */
    val SYMBOLS: List<String> = listOf(
        PAD, SPACE, STRESS,
        ".", ",", "?", "!", ";", ":",
        "a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o",
        "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr", "aʊər", "jʊr",
        "b", "d", "f", "g", "h", "j", "k", "l", "m", "n", "ŋ", "p", "r", "ɹ", "s", "ʃ", "t",
        "tʃ", "θ", "ð", "v", "w", "z", "ʒ", "dʒ", "x", "ks", "kw", "sk", "skr", "ʃr", "spl",
        "spr", "str", "θr", "ər", "ʃən", "ʒən", "tʃər",
    ).distinct()

    /** Symbol -> id, built once. */
    val INDEX: Map<String, Int> = SYMBOLS.withIndex().associate { (index, symbol) -> symbol to index }

    fun idOf(symbol: String): Int = INDEX[symbol] ?: 0

    /** Tokens that carry a vowel, used to place the stress mark. */
    private val VOWELS = setOf(
        "a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o",
        "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr", "aʊər", "jʊr",
    )

    /**
     * Ordered English grapheme rules. Longest patterns first, because "tch" has to beat "ch" and
     * "igh" has to beat "i"+"g"+"h".
     */
    private val ENGLISH_RULES: List<Pair<String, String>> = listOf(
        "ough" to "ɔ", "augh" to "ɔ", "eigh" to "eɪ",
        "tion" to "ʃən", "sion" to "ʒən", "ture" to "tʃər",
        "tch" to "tʃ", "dge" to "dʒ", "igh" to "aɪ",
        "air" to "ɛr", "ear" to "ɪr", "eer" to "ɪr", "oor" to "ɔr", "our" to "aʊər",
        "ure" to "jʊr", "sch" to "sk", "scr" to "skr", "shr" to "ʃr",
        "spl" to "spl", "spr" to "spr", "str" to "str", "thr" to "θr",
        "ch" to "tʃ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w",
        "ck" to "k", "ng" to "ŋ", "qu" to "kw", "gh" to "", "kn" to "n", "wr" to "r", "mn" to "m",
        "ee" to "i", "ea" to "i", "oo" to "u", "ou" to "aʊ", "ow" to "aʊ", "oa" to "oʊ",
        "ai" to "eɪ", "ay" to "eɪ", "oi" to "ɔɪ", "oy" to "ɔɪ", "au" to "ɔ", "aw" to "ɔ",
        "ie" to "aɪ", "ei" to "eɪ", "ey" to "eɪ", "ue" to "u", "ui" to "u",
        // R-coloured vowels are a vowel followed by a rhotic, not an indivisible phone. Writing
        // them as two tokens gives the unit bank an /ɑ/ and an /ɹ/ it can reuse in any word; the old
        // single token had to borrow a whole mislabelled "ar" from another language when the
        // speaker had never said one.
        "ar" to "ɑ ɹ", "er" to "ə ɹ", "ir" to "ɜ ɹ", "ur" to "ɜ ɹ", "or" to "ɔ ɹ",
    ).sortedByDescending { it.first.length }

    /** One letter, one approximate phoneme. Soft c and g are handled before this. */
    private val LETTERS: Map<Char, String> = mapOf(
        'a' to "æ", 'b' to "b", 'c' to "k", 'd' to "d", 'e' to "ɛ", 'f' to "f", 'g' to "g",
        'h' to "h", 'i' to "ɪ", 'j' to "dʒ", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
        'o' to "ɑ", 'p' to "p", 'q' to "k", 'r' to "ɹ", 's' to "s", 't' to "t", 'u' to "ʌ",
        'v' to "v", 'w' to "w", 'x' to "ks", 'y' to "j", 'z' to "z",
    )

    /** Accented Latin letters lose their accent rather than becoming silence. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    /**
     * Phonemises [text].
     *
     * @param language a BCP-47-ish tag; anything beginning "en" gets the English rules, everything
     *        else is read letter by letter.
     */
    fun phonemize(text: String, language: String = "en"): List<String> {
        val english = language.trim().lowercase(Locale.ROOT).startsWith("en") || language.isBlank()
        val tokens = mutableListOf<String>()
        val folded = fold(text)
        var index = 0
        while (index < folded.length) {
            val ch = folded[index]
            when {
                ch.isWhitespace() -> {
                    if (tokens.isNotEmpty() && tokens.last() != SPACE) tokens += SPACE
                    index++
                }
                ch.isDigit() -> {
                    var end = index
                    while (end < folded.length && folded[end].isDigit()) end++
                    val words = numberWords(folded.substring(index, end))
                    words.forEach { word -> tokens += wordTokens(word, english) }
                    index = end
                }
                ch.isLetter() || ch == '\'' -> {
                    var end = index
                    while (end < folded.length && (folded[end].isLetter() || folded[end] == '\'')) end++
                    val word = folded.substring(index, end).trim('\'')
                    if (word.isNotEmpty()) tokens += wordTokens(word, english)
                    index = end
                }
                ch in ",.!?;:" -> {
                    tokens += ch.toString()
                    index++
                }
                else -> index++
            }
        }
        while (tokens.lastOrNull() == SPACE) tokens.removeAt(tokens.size - 1)
        return tokens
    }

    /** Phonemises and folds punctuation into pauses, for synthesis: one string of symbols. */
    fun phonemes(text: String, language: String = "en"): String =
        phonemize(text, language).joinToString("")

    private fun wordTokens(word: String, english: Boolean): List<String> {
        val raw = if (english) englishPhonemes(word) else letterTokens(word)
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        // One primary stress per word, before the first vowel. A model that sees no stress predicts
        // flat durations, which is the difference between "record" and "record" never being learned.
        val firstVowel = raw.indexOfFirst { it in VOWELS }
        raw.forEachIndexed { index, token ->
            if (index == firstVowel) out += STRESS
            out += token
        }
        return out
    }

    /**
     * Words the rules cannot be trusted with, written out once.
     *
     * English spelling is not a function; `the`, `love`, `cookies` and `hello` are irregular in
     * ways no ordered rule set gets right for every sentence. These are the high-frequency words a
     * prompt is actually made of, plus the words the archive's own transcripts contain. A word not
     * here still goes through the rules below, so the lexicon improves the common path without
     * making the uncommon one silent.
     */
    private val LEXICON: Map<String, List<String>> = buildMap {
        fun entry(word: String, phonemes: String) {
            put(word, phonemes.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
        }
        entry("a", "ə"); entry("an", "æ n"); entry("the", "ð ə"); entry("to", "t u")
        entry("of", "ʌ v"); entry("and", "æ n d"); entry("or", "ɔr"); entry("for", "f ɔr")
        entry("from", "f ɹ ʌ m"); entry("with", "w ɪ ð"); entry("without", "w ɪ ð aʊ t")
        entry("in", "ɪ n"); entry("on", "ɑ n"); entry("at", "æ t"); entry("as", "æ z")
        entry("by", "b aɪ"); entry("is", "ɪ z"); entry("are", "ɑr"); entry("was", "w ʌ z")
        entry("were", "w ɜr"); entry("be", "b i"); entry("been", "b ɪ n"); entry("am", "æ m")
        entry("i", "aɪ"); entry("you", "j u"); entry("he", "h i"); entry("she", "ʃ i")
        entry("it", "ɪ t"); entry("we", "w i"); entry("me", "m i"); entry("my", "m aɪ")
        entry("your", "j ɔ ɹ"); entry("our", "aʊ ə ɹ"); entry("their", "ð ɛ ɹ")
        entry("there", "ð ɛ ɹ"); entry("they", "ð eɪ"); entry("them", "ð ɛ m"); entry("then", "ð ɛ n")
        entry("than", "ð æ n"); entry("this", "ð ɪ s"); entry("that", "ð æ t")
        entry("these", "ð i z"); entry("those", "ð oʊ z"); entry("though", "ð oʊ")
        entry("through", "θ ɹ u"); entry("thought", "θ ɔ t"); entry("thing", "θ ɪ ŋ")
        entry("think", "θ ɪ ŋ k"); entry("three", "θ ɹ i"); entry("the", "ð ə")
        entry("have", "h æ v"); entry("has", "h æ z"); entry("had", "h æ d")
        entry("do", "d u"); entry("does", "d ʌ z"); entry("did", "d ɪ d"); entry("done", "d ʌ n")
        entry("can", "k æ n"); entry("could", "k ʊ d"); entry("would", "w ʊ d")
        entry("should", "ʃ ʊ d"); entry("will", "w ɪ l"); entry("shall", "ʃ æ l")
        entry("may", "m eɪ"); entry("might", "m aɪ t"); entry("must", "m ʌ s t")
        entry("not", "n ɑ t"); entry("no", "n oʊ"); entry("yes", "j ɛ s"); entry("all", "ɔ l")
        entry("any", "ɛ n i"); entry("some", "s ʌ m"); entry("many", "m ɛ n i")
        entry("more", "m ɔr"); entry("most", "m oʊ s t"); entry("very", "v ɛr i")
        entry("just", "dʒ ʌ s t"); entry("only", "oʊ n l i"); entry("also", "ɔ l s oʊ")
        entry("into", "ɪ n t u"); entry("about", "ə b aʊ t"); entry("over", "oʊ v ər")
        entry("under", "ʌ n d ər"); entry("again", "ə g ɛ n"); entry("because", "b ɪ k ɔ z")
        entry("before", "b ɪ f ɔr"); entry("after", "æ f t ər"); entry("where", "w ɛr")
        entry("when", "w ɛ n"); entry("what", "w ʌ t"); entry("who", "h u"); entry("why", "w aɪ")
        entry("how", "h aʊ"); entry("which", "w ɪ tʃ"); entry("while", "w aɪ l")
        entry("work", "w ɜr k");        entry("word", "w ɜ ɹ d"); entry("words", "w ɜ ɹ d z")
        entry("world", "w ɜ ɹ l d"); entry("voice", "v ɔɪ s"); entry("voices", "v ɔɪ s ɪ z")
        entry("language", "l æ ŋ g w ɪ dʒ"); entry("english", "ɪ ŋ g l ɪ ʃ")
        entry("audio", "ɔ d i oʊ");        entry("sample", "s æ m p əl"); entry("clear", "k l ɪ ɹ")
        entry("clearly", "k l ɪ ɹ l i"); entry("speak", "s p i k"); entry("speaking", "s p i k ɪ ŋ")
        entry("read", "ɹ i d"); entry("please", "p l i z"); entry("hello", "h ə l oʊ")
        entry("welcome", "w ɛ l k ə m"); entry("good", "g ʊ d"); entry("great", "g ɹ eɪ t")
        entry("love", "l ʌ v"); entry("cookies", "k ʊ k i z"); entry("cookie", "k ʊ k i")
        entry("man", "m æ n"); entry("woman", "w ʊ m ə n"); entry("people", "p i p əl")
        entry("human", "h j u m ə n"); entry("sound", "s aʊ n d"); entry("music", "m j u z ɪ k")
        entry("easy", "i z i"); entry("understand", "ʌ n d ər s t æ n d")
        entry("sentence", "s ɛ n t ə n s"); entry("archive", "ɑ ɹ k aɪ v")
        entry("female", "f i m eɪ l"); entry("male", "m eɪ l"); entry("neutral", "n u t ɹ ə l")
        entry("accent", "æ k s ɛ n t"); entry("demonstrate", "d ɛ m ə n s t ɹ eɪ t")
        entry("pronunciation", "p ɹ ə n ʌ n s i eɪ ʃ ə n"); entry("give", "g ɪ v")
        entry("live", "l ɪ v"); entry("move", "m u v"); entry("come", "k ʌ m")
        entry("one", "w ʌ n"); entry("once", "w ʌ n s"); entry("gone", "g ɔ n")
        entry("put", "p ʊ t"); entry("push", "p ʊ ʃ"); entry("pull", "p ʊ l")
        entry("book", "b ʊ k"); entry("look", "l ʊ k"); entry("took", "t ʊ k")
        entry("food", "f u d"); entry("room", "ɹ u m"); entry("soon", "s u n")
        entry("bread", "b ɹ ɛ d"); entry("head", "h ɛ d"); entry("friend", "f ɹ ɛ n d")
        entry("field", "f i l d"); entry("heart", "h ɑr t"); entry("bear", "b ɛr")
        entry("key", "k i"); entry("show", "ʃ oʊ"); entry("know", "n oʊ"); entry("low", "l oʊ")
        entry("slow", "s l oʊ"); entry("snow", "s n oʊ"); entry("grow", "g ɹ oʊ")
        entry("throw", "θ ɹ oʊ"); entry("window", "w ɪ n d oʊ"); entry("tomorrow", "t ə m ɑr oʊ")
        entry("talk", "t ɔ k"); entry("walk", "w ɔ k"); entry("water", "w ɔ t ər")
        entry("her", "h ɜ ɹ"); entry("here", "h ɪ ɹ"); entry("near", "n ɪ ɹ"); entry("dear", "d ɪ ɹ")
        entry("four", "f ɔ ɹ"); entry("your", "j ɔ ɹ"); entry("sure", "ʃ ʊ ɹ")
        entry("table", "t eɪ b əl"); entry("little", "l ɪ t əl"); entry("people", "p i p əl")
        entry("because", "b ɪ k ɔ z"); entry("beautiful", "b j u t ɪ f əl")
        entry("machine", "m ə ʃ i n"); entry("school", "s k u l"); entry("child", "tʃ aɪ l d")
        entry("find", "f aɪ n d"); entry("kind", "k aɪ n d"); entry("mind", "m aɪ n d")
        entry("cold", "k oʊ l d"); entry("gold", "g oʊ l d"); entry("old", "oʊ l d")
        entry("post", "p oʊ s t"); entry("light", "l aɪ t"); entry("night", "n aɪ t")
        entry("right", "ɹ aɪ t"); entry("high", "h aɪ"); entry("buy", "b aɪ")
        entry("reply", "ɹ ɪ p l aɪ"); entry("apply", "ə p l aɪ"); entry("why", "w aɪ")
        entry("city", "s ɪ t i"); entry("pretty", "p ɹ ɪ t i"); entry("happy", "h æ p i")
        entry("hello", "h ə l oʊ"); entry("cosmo", "k ɑ z m oʊ"); entry("iris", "aɪ ɹ ɪ s")
        entry("lumen", "l u m ɛ n"); entry("naksh", "n ɑ k ʃ"); entry("orion", "oʊ ɹ aɪ ə n")
    }

    /** Vowel letters whose sound changes before a final silent e. */
    private val LONG_VOWEL = mapOf('a' to "eɪ", 'e' to "i", 'i' to "aɪ", 'o' to "oʊ", 'u' to "u")

    private val VOWEL_TOKENS: Set<String> = VOWELS

    private val VOICED_FINALS: Set<String> = VOWEL_TOKENS + setOf(
        "b", "d", "g", "v", "ð", "z", "ʒ", "dʒ", "m", "n", "ŋ", "l", "ɹ", "r", "w", "j",
    )

    private val SIBILANT_FINALS: Set<String> = setOf("s", "z", "ʃ", "ʒ", "tʃ", "dʒ")

    private fun englishPhonemes(word: String): List<String> {
        val lower = word.lowercase(Locale.ROOT).replace("’", "'").trim('\'')
        if (lower.isEmpty()) return emptyList()
        LEXICON[lower]?.let { return it }

        // Plurals and third-person -s. The ending is chosen from the sound before it, which is the
        // difference between "cookies" (z), "cats" (s) and "voices" (ɪz).
        if (lower.length > 3 && lower.endsWith("es") && !lower.endsWith("ees")) {
            val stem = lower.dropLast(2)
            val base = englishPhonemes(stem)
            val last = base.lastOrNull()
            return base + if (last in SIBILANT_FINALS) listOf("ɪ", "z") else listOf("z")
        }
        if (lower.length > 2 && lower.endsWith("s") && !lower.endsWith("ss") && !lower.endsWith("us")) {
            val base = englishPhonemes(lower.dropLast(1))
            val last = base.lastOrNull()
            return base + when {
                last in SIBILANT_FINALS -> listOf("ɪ", "z")
                last in VOICED_FINALS -> listOf("z")
                else -> listOf("s")
            }
        }

        // Past tense -ed: wanted, walked, loved. The vowel an English speaker inserts after t/d is
        // the whole difference between "want-id" and "wantt".
        if (lower.length > 3 && lower.endsWith("ed") && !lower.endsWith("eed")) {
            val stem = lower.dropLast(2)
            val base = englishPhonemes(stem)
            val last = base.lastOrNull()
            return base + when {
                last == "t" || last == "d" -> listOf("ɪ", "d")
                last in setOf("p", "k", "f", "θ", "s", "ʃ", "tʃ") -> listOf("t")
                else -> listOf("d")
            }
        }

        // -ing keeps the stem's own pronunciation; a stem that only exists with a silent e is
        // looked up with that e restored (making -> make, coming -> come).
        if (lower.length > 4 && lower.endsWith("ing")) {
            val stem = lower.dropLast(3)
            val restored = LEXICON[stem + "e"]
            val base = restored ?: englishPhonemes(stem)
            return base + listOf("ɪ", "ŋ")
        }

        // -ly, -ness, -ment, -ful, -less are productive enough to spell out.
        if (lower.length > 3 && lower.endsWith("ly")) {
            return englishPhonemes(lower.dropLast(2)) + listOf("l", "i")
        }
        if (lower.length > 4 && lower.endsWith("ness")) {
            return englishPhonemes(lower.dropLast(4)) + listOf("n", "ə", "s")
        }
        if (lower.length > 4 && lower.endsWith("ment")) {
            return englishPhonemes(lower.dropLast(4)) + listOf("m", "ə", "n", "t")
        }
        if (lower.length > 3 && lower.endsWith("ful")) {
            return englishPhonemes(lower.dropLast(3)) + listOf("f", "ə", "l")
        }
        if (lower.length > 4 && lower.endsWith("less")) {
            return englishPhonemes(lower.dropLast(4)) + listOf("l", "ə", "s")
        }

        // Final -y: a one-syllable word says /aɪ/ (my, cry), a longer one says /i/ (lazy, city).
        if (lower.length > 2 && lower.endsWith("y")) {
            val base = englishPhonemes(lower.dropLast(1))
            return base + if (lower.length <= 3) listOf("aɪ") else listOf("i")
        }

        // A consonant + le ending is syllabic /əl/: table, little, purple.
        if (lower.length > 3 && lower.endsWith("le") && lower[lower.length - 3] !in "aeiouy") {
            return englishPhonemes(lower.dropLast(2)) + listOf("ə", "l")
        }

        // Magic e: the vowel before the final consonant says its name. Irregular words are in the
        // lexicon above; this covers the regular long-vowel pattern the old rules missed entirely.
        val magicE = lower.length > 2 && lower.endsWith("e") &&
            !lower.endsWith("ee") && !lower.endsWith("ie") && !lower.endsWith("ue")
        val text = if (magicE) lower.dropLast(1) else lower
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val ch = text[index]

            // The long vowel of a magic-e word sits immediately before its final consonant.
            if (magicE && index == text.length - 2 && ch in LONG_VOWEL &&
                text[index + 1].isLetter() && text[index + 1] !in "aeiouy"
            ) {
                if (ch == 'u' && index > 0 && text[index - 1] !in "rl") {
                    tokens += "j"
                }
                tokens += LONG_VOWEL.getValue(ch)
                index++
                continue
            }

            // Soft c and g before a front vowel: "city" and "gem", not "kity" and "gem" with a hard g.
            if ((ch == 'c' || ch == 'g') && index + 1 < text.length && text[index + 1] in "eiy") {
                tokens += if (ch == 'c') "s" else "dʒ"
                index++
                continue
            }

            // s between vowels is /z/: easy, music, laser. The plural suffix above has already run.
            if (ch == 's' && index > 0 && index + 1 < text.length &&
                text[index - 1] in "aeiouy" && text[index + 1] in "aeiouy"
            ) {
                tokens += "z"
                index++
                continue
            }

            val rule = ENGLISH_RULES.firstOrNull { (pattern, _) ->
                pattern.isNotEmpty() && text.startsWith(pattern, index)
            }
            if (rule != null) {
                if (rule.second.isNotEmpty()) {
                    tokens += rule.second.split(Regex("\\s+")).filter { it.isNotEmpty() }
                }
                index += rule.first.length
                continue
            }

            // A doubled consonant is one consonant; doubled vowels are handled by the rules above.
            if (index + 1 < text.length && text[index + 1] == ch &&
                ch !in "aeiouy" && ch.isLetter()
            ) {
                tokens += LETTERS[ch] ?: ""
                index += 2
                continue
            }

            val mapped = LETTERS[ch]
            if (mapped != null) {
                tokens += mapped
            } else if (ch.isLetter()) {
                tokens += "ə"
            }
            index++
        }
        return tokens.filter { it.isNotEmpty() }
    }

    private fun letterTokens(word: String): List<String> {
        val tokens = mutableListOf<String>()
        for (ch in word.lowercase(Locale.ROOT)) {
            when {
                LETTERS.containsKey(ch) -> tokens += LETTERS.getValue(ch)
                ch.isLetter() -> tokens += if (ch.code % 3 == 0) "a" else "ə"
                else -> Unit
            }
        }
        return tokens
    }

    /** "2024" -> "two zero two four": digits, which is what identifiers and years need. */
    private fun numberWords(digits: String): List<String> {
        val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        return digits.mapNotNull { it.digitToIntOrNull()?.let(words::get) }
    }
}

/**
 * The audio half of a speech model: mel analysis, a speaker embedding, and a vocoder.
 *
 * ## The vocoder is Griffin-Lim, and that is a choice rather than a shortcut
 *
 * A neural vocoder (HiFi-GAN and its descendants) is trained adversarially against tens of hours of
 * audio; it is not something a device can train from a user's folder in an afternoon, and shipping
 * one as a fixed download would make the "trained model" a wrapper around someone else's voice. What
 * is here is the classical analysis/synthesis vocoder: the mel filterbank is inverted to a magnitude
 * spectrum, and Griffin-Lim recovers a phase consistent with it by alternating between the time and
 * frequency domains. It is intelligible on clean speech, it is deterministic, and it runs in
 * milliseconds per second of audio.
 *
 * The artifact's metadata names it, its iteration count and its window, so nobody has to guess which
 * vocoder produced a WAV.
 *
 * ## Everything is derived from the sample rate
 *
 * Window, hop and FFT size come from [Config], which the trainer writes from the recipe's sample
 * rate. A model trained at 22.05 kHz and exercised at 16 kHz would otherwise silently change what a
 * mel band means.
 */
object TtsSignal {

    /** Bands in the mel spectrogram every speech model here predicts. */
    const val BANDS = 40

    /** A 32 ms analysis window: long enough for a 100 Hz pitch period, short enough for a consonant. */
    const val WINDOW_MS = 32

    /** A 10 ms hop: 100 frames per second, the rate every TTS duration model counts in. */
    const val HOP_MS = 10

    /** Dimensions of [speakerVector]. */
    const val SPEAKER_DIMENSIONS = 20

    /** How the signal was measured, written into the model so a reader can reproduce it. */
    data class Config(val sampleRate: Int) {
        val fftSize: Int = nextPowerOfTwo((sampleRate * WINDOW_MS / 1000.0).toInt().coerceAtLeast(256))
        val hop: Int = max(1, sampleRate * HOP_MS / 1000)
        val bins: Int = fftSize / 2 + 1
    }

    /** One analysed frame: the log-mel energies, plus what the speaker encoder needs. */
    class Frame(
        val logMel: DoubleArray,
        val f0: Double,
        val rms: Double,
        val centroid: Double,
        val flatness: Double,
        val tilt: Double,
    )

    /**
     * Analyses [samples] into one frame per 10 ms.
     *
     * The values are natural-log energies, floored at 1e-8 so a silent frame is a small number rather
     * than negative infinity -- a target of -inf makes every gradient NaN.
     */
    fun analyse(samples: FloatArray, config: Config): List<Frame> {
        if (samples.isEmpty()) return emptyList()
        val fftSize = config.fftSize
        val hop = config.hop
        val filters = filters(config)
        val window = hann(fftSize)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val frames = ArrayList<Frame>((samples.size / hop) + 1)

        var start = 0
        while (start < samples.size) {
            var energy = 0.0
            for (index in 0 until fftSize) {
                val sample = if (start + index < samples.size) samples[start + index].toDouble() else 0.0
                energy += sample * sample
                re[index] = sample * window[index]
                im[index] = 0.0
            }
            val rms = sqrt(energy / fftSize)
            fft(re, im)
            val magnitude = DoubleArray(config.bins) { bin -> sqrt(re[bin] * re[bin] + im[bin] * im[bin]) }
            val logMel = DoubleArray(BANDS)
            for (band in 0 until BANDS) {
                var sum = 0.0
                val filter = filters[band]
                for (bin in filter.indices) sum += filter[bin] * magnitude[bin] * magnitude[bin]
                logMel[band] = ln(sum + 1e-8)
            }
            val total = magnitude.sum().coerceAtLeast(1e-12)
            var centroid = 0.0
            var flatLog = 0.0
            var flatCount = 0
            for (bin in 0 until config.bins) {
                centroid += bin.toDouble() * config.sampleRate / fftSize * magnitude[bin]
                if (magnitude[bin] > 0.0) {
                    flatLog += log10(magnitude[bin])
                    flatCount++
                }
            }
            centroid /= total
            val flatness = if (flatCount == 0) 0.0 else {
                (10.0.pow(flatLog / flatCount) / (total / config.bins)).coerceIn(0.0, 1.0)
            }
            val tilt = tiltOf(magnitude, config)
            frames += Frame(logMel, pitchOf(samples, start, config), rms, centroid, flatness, tilt)
            start += hop
        }
        return frames
    }

    /**
     * A 20-number description of a voice, from a reference waveform.
     *
     * The same function is used to condition training and to clone at inference. That is the whole
     * mechanism: a model trained with this vector attached to every clip learns what a voice's
     * statistics imply about its acoustics, and a new recording becomes that vector rather than a
     * new model. Pitch behaviour, brightness, tilt, noise and eight band energies are enough to
     * distinguish most speakers, and the vector is fixed-size so the network needs nothing else.
     */
    fun speakerVector(samples: FloatArray, config: Config): DoubleArray {
        val frames = analyse(samples, config)
        if (frames.isEmpty()) return DoubleArray(SPEAKER_DIMENSIONS)
        val voiced = frames.filter { !it.f0.isNaN() }
        val pitch = voiced.map { it.f0 }.sorted()
        val f0Mean = if (pitch.isEmpty()) 0.0 else pitch.average()
        val f0Std = if (pitch.isEmpty()) 0.0 else sqrt(pitch.sumOf { (it - f0Mean) * (it - f0Mean) } / pitch.size)
        val f0Min = pitch.firstOrNull() ?: 0.0
        val f0Max = pitch.lastOrNull() ?: 0.0
        val centroids = frames.map { it.centroid }
        val centroidMean = centroids.average()
        val centroidStd = sqrt(centroids.sumOf { (it - centroidMean) * (it - centroidMean) } / centroids.size)
        val rmsMean = frames.map { it.rms }.average()
        val rmsStd = sqrt(frames.sumOf { (it.rms - rmsMean) * (it.rms - rmsMean) } / frames.size)
        // A frame with no energy in either tilt band measures 0/0. That is one unmeasurable frame,
        // not a property of the voice, so it must not turn the whole 20-number vector into NaN --
        // one NaN here conditions every training frame and makes the entire TTS loss NaN.
        val tilts = frames.map { it.tilt }.filter { it.isFinite() }
        val flatnesses = frames.map { it.flatness }.filter { it.isFinite() }

        // Eight band groups over the 40 mel bands: a coarse long-term spectrum, which is the part of
        // a voice that survives a change of words.
        val bandMeans = DoubleArray(8)
        val total = DoubleArray(BANDS)
        for (frame in frames) {
            for (band in 0 until BANDS) total[band] += exp(frame.logMel[band])
        }
        val grand = total.sum().coerceAtLeast(1e-9)
        for (group in 0 until 8) {
            var sum = 0.0
            for (band in group * (BANDS / 8) until min(BANDS, (group + 1) * (BANDS / 8))) {
                sum += total[band] / frames.size
            }
            bandMeans[group] = sum / (grand / frames.size)
        }

        return doubleArrayOf(
            log2(f0Mean / 120.0),
            ln(1.0 + f0Std / 10.0),
            log2(f0Min / 120.0),
            log2(f0Max / 120.0),
            centroidMean / 3_000.0,
            centroidStd / 1_000.0,
            (if (tilts.isEmpty()) 0.0 else tilts.average()) / 12.0,
            if (flatnesses.isEmpty()) 0.0 else flatnesses.average(),
            bandMeans[0], bandMeans[1], bandMeans[2], bandMeans[3],
            bandMeans[4], bandMeans[5], bandMeans[6], bandMeans[7],
            voiced.size.toDouble() / frames.size,
            (rmsMean * 40.0).coerceAtMost(4.0),
            (rmsStd * 80.0).coerceAtMost(4.0),
            frames.map { if (it.f0.isNaN()) 0.0 else 1.0 }.average(),
        ).let { vector -> DoubleArray(vector.size) { if (vector[it].isFinite()) vector[it] else 0.0 } }
    }

    /**
     * Turns [mel] frames back into a waveform.
     *
     * The mel filterbank is inverted by distributing each band's energy over the bins its filter
     * covers, weighted by the filter and normalised by the filter's own energy. That gives a
     * magnitude spectrum whose mel projection is the input; Griffin-Lim then finds a phase that is
     * consistent with it. [iterations] is the quality/time knob: 4 is rough, 16 is intelligible, 32 is
     * where this implementation stops hearing the difference; the default is 32 because generated
     * mel frames are not clean analysis frames and need the extra passes.
     */
    fun vocode(
        mel: List<DoubleArray>,
        config: Config,
        seed: Long = 42L,
        iterations: Int = 32,
    ): FloatArray {
        if (mel.isEmpty()) return FloatArray(0)
        val fftSize = config.fftSize
        val hop = config.hop
        val bins = config.bins
        val filters = filters(config)
        val filterEnergy = DoubleArray(BANDS) { band ->
            filters[band].sumOf { it * it }.coerceAtLeast(1e-9)
        }
        val magnitudes = Array(mel.size) { frame ->
            val energy = DoubleArray(BANDS) { band -> exp(mel[frame][band]) }
            val power = DoubleArray(bins)
            for (band in 0 until BANDS) {
                val filter = filters[band]
                val scale = energy[band] / filterEnergy[band]
                for (bin in filter.indices) power[bin] += filter[bin] * scale
            }
            DoubleArray(bins) { bin -> sqrt(max(power[bin], 1e-10)) }
        }

        val length = hop * (mel.size - 1) + fftSize
        val window = hann(fftSize)
        val out = DoubleArray(length)
        val windowSum = DoubleArray(length)
        for (frame in 0 until mel.size) {
            for (index in 0 until fftSize) {
                val position = frame * hop + index
                if (position < length) windowSum[position] += window[index] * window[index]
            }
        }

        // Phase starts as deterministic noise rather than zeros: all-zero phase is a symmetric pulse
        // in every frame, which is an audible buzz before the first iteration fixes it.
        val random = java.util.Random(seed)
        val phase = Array(mel.size) { DoubleArray(bins) { random.nextDouble() * 2.0 * PI - PI } }
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)

        repeat(iterations.coerceIn(1, 64)) {
            java.util.Arrays.fill(out, 0.0)
            // Inverse: spectrum with the current phase, back to the time domain, overlap-add.
            for (frame in 0 until mel.size) {
                java.util.Arrays.fill(re, 0.0)
                java.util.Arrays.fill(im, 0.0)
                for (bin in 0 until bins) {
                    re[bin] = magnitudes[frame][bin] * cos(phase[frame][bin])
                    im[bin] = magnitudes[frame][bin] * sin(phase[frame][bin])
                }
                // DC and Nyquist are real bins in a real signal. Giving them an imaginary part
                // makes the inverse produce a complex conjugate pair that cannot be a waveform,
                // which shows up as a DC thump and a metallic edge in every frame.
                re[0] = magnitudes[frame][0]
                im[0] = 0.0
                re[bins - 1] = magnitudes[frame][bins - 1]
                im[bins - 1] = 0.0
                // A real signal needs a conjugate-symmetric spectrum above Nyquist.
                for (bin in 1 until bins - 1) {
                    re[fftSize - bin] = re[bin]
                    im[fftSize - bin] = -im[bin]
                }
                ifft(re, im)
                for (index in 0 until fftSize) {
                    val position = frame * hop + index
                    if (position < length) out[position] += re[index] * window[index]
                }
            }
            for (position in 0 until length) {
                val sum = windowSum[position]
                out[position] = if (sum > 1e-8) out[position] / sum else 0.0
            }
            // Analysis: the phase of the signal we just built becomes the next estimate.
            for (frame in 0 until mel.size) {
                java.util.Arrays.fill(re, 0.0)
                java.util.Arrays.fill(im, 0.0)
                for (index in 0 until fftSize) {
                    val position = frame * hop + index
                    if (position < length) re[index] = out[position] * window[index]
                }
                fft(re, im)
                for (bin in 0 until bins) phase[frame][bin] = atan2(im[bin], re[bin])
            }
        }

        // Final reconstruction with the converged phase.
        java.util.Arrays.fill(out, 0.0)
        for (frame in 0 until mel.size) {
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (bin in 0 until bins) {
                re[bin] = magnitudes[frame][bin] * cos(phase[frame][bin])
                im[bin] = magnitudes[frame][bin] * sin(phase[frame][bin])
            }
            re[0] = magnitudes[frame][0]
            im[0] = 0.0
            re[bins - 1] = magnitudes[frame][bins - 1]
            im[bins - 1] = 0.0
            for (bin in 1 until bins - 1) {
                re[fftSize - bin] = re[bin]
                im[fftSize - bin] = -im[bin]
            }
            ifft(re, im)
            for (index in 0 until fftSize) {
                val position = frame * hop + index
                if (position < length) out[position] += re[index] * window[index]
            }
        }
        for (position in 0 until length) {
            val sum = windowSum[position]
            out[position] = if (sum > 1e-8) out[position] / sum else 0.0
        }

        // Remove the DC term the filterbank inversion can leave behind, then put the signal at a
        // consistent speech level. The old path multiplied by 1.8 and only limited the peak, which
        // made the output loud enough to sit near full scale on every utterance -- harsh on its own,
        // and worse once an imperfect mel frame produced an overshoot. Level is set by RMS, with a
        // peak limiter as the guard, rather than by whichever frame happened to be loudest.
        var mean = 0.0
        for (value in out) mean += value
        mean /= length.coerceAtLeast(1)
        val samples = FloatArray(length) { index ->
            val value = out[index] - mean
            (value / (1.0 + abs(value))).toFloat()
        }
        val rawRms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size.coerceAtLeast(1))
        val targetRms = 0.14
        // Generated mel frames are often far quieter than a clean analysis frame -- the decoder
        // predicts an average energy, not a recording level -- so the gain ceiling has to allow a
        // very quiet signal to reach speech level. The peak limiter below is still the guard; this
        // number only says how much quiet is still worth amplifying.
        val gain = if (rawRms > 1e-8) (targetRms / rawRms).coerceIn(0.2, 5_000.0) else 1.0
        if (gain != 1.0) {
            for (index in samples.indices) samples[index] = (samples[index] * gain).toFloat()
        }
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0.95f) {
            val scale = 0.95f / peak
            for (index in samples.indices) samples[index] *= scale
        }
        val fade = min(config.sampleRate / 50, samples.size / 2)
        for (index in 0 until fade) {
            val gain = index.toFloat() / fade
            samples[index] *= gain
            samples[samples.size - 1 - index] *= gain
        }
        return samples
    }

    // ── DSP ──────────────────────────────────────────────────────────────────────────────────────

    /** Triangular mel filters, [BANDS] of them, from 40 Hz to just under Nyquist. */
    fun filters(config: Config): Array<DoubleArray> {
        val bins = config.bins
        val low = 40.0
        val high = config.sampleRate / 2.0 * 0.97
        val lowMel = melOf(low)
        val highMel = melOf(high)
        val points = DoubleArray(BANDS + 2) { index ->
            inverseMel(lowMel + (highMel - lowMel) * index / (BANDS + 1)) * config.fftSize / config.sampleRate
        }
        return Array(BANDS) { band ->
            val filter = DoubleArray(bins)
            val left = points[band]
            val centre = points[band + 1]
            val right = points[band + 2]
            for (bin in 0 until bins) {
                filter[bin] = when {
                    bin <= left || bin >= right -> 0.0
                    bin <= centre -> (bin - left) / (centre - left).coerceAtLeast(1e-9)
                    else -> (right - bin) / (right - centre).coerceAtLeast(1e-9)
                }
            }
            filter
        }
    }

    private fun melOf(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun inverseMel(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun hann(size: Int): DoubleArray =
        DoubleArray(size) { index -> 0.5 - 0.5 * cos(2.0 * PI * index / size) }

    private fun log2(value: Double): Double = ln(value.coerceAtLeast(1e-6)) / ln(2.0)

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }

    /** Difference between low- and high-band energy, in dB per octave. */
    private fun tiltOf(magnitude: DoubleArray, config: Config): Double {
        var low = 0.0
        var lowCount = 0
        var high = 0.0
        var highCount = 0
        for (bin in 1 until config.bins) {
            val hertz = bin.toDouble() * config.sampleRate / config.fftSize
            when {
                hertz in 200.0..1_000.0 -> {
                    low += magnitude[bin]
                    lowCount++
                }
                hertz in 1_000.0..4_000.0 -> {
                    high += magnitude[bin]
                    highCount++
                }
            }
        }
        val lowMean = if (lowCount == 0) 0.0 else low / lowCount
        val highMean = if (highCount == 0) 0.0 else high / highCount
        // Silence has no low-band and no high-band energy: 0/0 is not a tilt, it is a frame with
        // nothing to measure. Returning 0 keeps the frame finite without inventing a slope.
        if (lowMean <= 0.0 || highMean <= 0.0) return 0.0
        val ratio = lowMean / highMean
        if (!ratio.isFinite() || ratio <= 0.0) return 0.0
        return 20.0 * log10(ratio) / 2.0
    }

    /** Autocorrelation pitch for one frame; NaN when the frame has no clear period. */
    private fun pitchOf(samples: FloatArray, start: Int, config: Config): Double {
        val size = min(config.fftSize, samples.size - start)
        if (size < config.sampleRate / 400) return Double.NaN
        val minLag = max(2, config.sampleRate / 400)
        val maxLag = min(size / 2, config.sampleRate / 70)
        if (maxLag <= minLag) return Double.NaN
        var energy = 0.0
        for (index in 0 until size) {
            val sample = samples[start + index].toDouble()
            energy += sample * sample
        }
        if (energy < 1e-7) return Double.NaN
        var bestLag = 0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var normA = 0.0
            var normB = 0.0
            for (index in 0 until size - lag) {
                val a = samples[start + index].toDouble()
                val b = samples[start + index + lag].toDouble()
                sum += a * b
                normA += a * a
                normB += b * b
            }
            val correlation = sum / sqrt(normA * normB + 1e-12)
            if (correlation > best) {
                best = correlation
                bestLag = lag
            }
        }
        if (best < 0.32 || bestLag == 0) return Double.NaN
        return config.sampleRate.toDouble() / bestLag
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val aRe = re[start + k]
                    val aIm = im[start + k]
                    val bRe = re[start + k + half] * curRe - im[start + k + half] * curIm
                    val bIm = re[start + k + half] * curIm + im[start + k + half] * curRe
                    re[start + k] = aRe + bRe
                    im[start + k] = aIm + bIm
                    re[start + k + half] = aRe - bRe
                    im[start + k + half] = aIm - bIm
                    val nextRe = curRe * stepRe - curIm * stepIm
                    curIm = curRe * stepIm + curIm * stepRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    /** Inverse FFT: conjugate, forward, conjugate, scale. */
    private fun ifft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (index in 0 until n) im[index] = -im[index]
        fft(re, im)
        for (index in 0 until n) {
            re[index] /= n
            im[index] = -im[index] / n
        }
    }
}
