package com.prism.launcher.writer

import com.prism.launcher.PrismSettings

/**
 * The user's own words: named dictionaries, and words they have redefined.
 *
 * ## Two different things, deliberately kept apart
 *
 * A DICTIONARY is a list of words that are correct — names, jargon, a game's vocabulary — so
 * autocorrect stops rewriting them. Adding "Prism" to a dictionary means "leave this alone".
 *
 * A REDEFINITION is a rewrite: type one thing, get another. "omw" becomes "on my way". These are
 * opposites in effect, and merging them into one list is how a keyboard ends up either mangling
 * names it should respect or refusing to expand shortcuts it should.
 */
object WriterUserDictionary {

    /** A named list of words that should never be corrected. */
    data class Dictionary(val name: String, val words: List<String>)

    // ── Dictionaries ───────────────────────────────────────────────────────

    fun dictionaries(): List<Dictionary> = decodeDictionaries(PrismSettings.getWriterDictionaries())

    fun saveDictionaries(all: List<Dictionary>) {
        PrismSettings.setWriterDictionaries(encodeDictionaries(all))
        cachedWords = null
    }

    fun addDictionary(name: String): Boolean {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return false
        val existing = dictionaries()
        if (existing.any { it.name.equals(cleaned, ignoreCase = true) }) return false
        saveDictionaries(existing + Dictionary(cleaned, emptyList()))
        return true
    }

    fun removeDictionary(name: String) {
        saveDictionaries(dictionaries().filterNot { it.name == name })
    }

    fun addWord(dictionary: String, word: String): Boolean {
        val cleaned = word.trim()
        if (cleaned.isEmpty()) return false
        val all = dictionaries().toMutableList()
        val index = all.indexOfFirst { it.name == dictionary }
        if (index < 0) return false
        val target = all[index]
        if (target.words.any { it.equals(cleaned, ignoreCase = true) }) return false
        all[index] = target.copy(words = target.words + cleaned)
        saveDictionaries(all)
        return true
    }

    fun removeWord(dictionary: String, word: String) {
        val all = dictionaries().toMutableList()
        val index = all.indexOfFirst { it.name == dictionary }
        if (index < 0) return
        all[index] = all[index].copy(words = all[index].words.filterNot { it == word })
        saveDictionaries(all)
    }

    /**
     * Every word across every dictionary, lowercased, cached.
     *
     * Consulted on each finished word, so it is built once and invalidated on write rather than
     * re-parsed from preferences per keystroke.
     */
    private var cachedWords: Set<String>? = null

    fun knowsWord(word: String): Boolean {
        val words = cachedWords ?: dictionaries()
            .flatMap { it.words }
            .map { it.lowercase() }
            .toSet()
            .also { cachedWords = it }
        return word.lowercase() in words
    }

    // ── Redefinitions ──────────────────────────────────────────────────────

    fun redefinitions(): Map<String, String> =
        PrismSettings.getWriterRedefinitions()
            .lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) return@mapNotNull null
                val from = line.substring(0, at).trim().lowercase()
                val to = line.substring(at + 1).trim()
                if (from.isEmpty() || to.isEmpty()) null else from to to
            }
            .toMap()

    fun setRedefinition(from: String, to: String): Boolean {
        val key = from.trim().lowercase()
        val value = to.trim()
        if (key.isEmpty() || value.isEmpty()) return false
        val merged = redefinitions() + (key to value)
        PrismSettings.setWriterRedefinitions(
            merged.entries.joinToString(NEWLINE) { "${it.key}=${it.value}" }
        )
        return true
    }

    fun removeRedefinition(from: String) {
        val merged = redefinitions() - from.trim().lowercase()
        PrismSettings.setWriterRedefinitions(
            merged.entries.joinToString(NEWLINE) { "${it.key}=${it.value}" }
        )
    }

    /**
     * The replacement for a typed word, or null.
     *
     * Case is carried across so "Omw" expands capitalised: a rewrite that always came back
     * lowercase would need fixing by hand every time it started a sentence.
     */
    fun redefine(typed: String): String? {
        val replacement = redefinitions()[typed.lowercase()] ?: return null
        return Autocorrect.matchCase(typed, replacement)
    }

    // ── Encoding ───────────────────────────────────────────────────────────
    //
    // One line per dictionary, `name\tword\tword`. Chosen over JSON because the file is written by
    // this object and read by this object, and a tab is not a character anyone puts in a word.

    private const val NEWLINE = "\n"
    private const val SEPARATOR = "\t"

    private fun encodeDictionaries(all: List<Dictionary>): String =
        all.joinToString(NEWLINE) { d ->
            (listOf(d.name) + d.words).joinToString(SEPARATOR)
        }

    private fun decodeDictionaries(raw: String): List<Dictionary> =
        raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) null else Dictionary(parts.first(), parts.drop(1))
            }
            .toList()
}
