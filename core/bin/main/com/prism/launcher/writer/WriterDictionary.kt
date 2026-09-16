package com.prism.launcher.writer

/**
 * The word list behind autocorrect and swipe decoding.
 *
 * FREQUENCY-ORDERED, WHICH IS THE WHOLE POINT. Two candidates are almost never equally likely: a
 * glide across `t-h-e` could be "the" or "thy", and a correction of "teh" could be "the" or "ted".
 * Ranking by how common a word actually is resolves nearly all of those without any cleverness,
 * which is why the list ships in frequency order rather than alphabetically.
 *
 * User words are held separately and always outrank the built-in list -- somebody who has typed a
 * name three times should stop having it corrected away.
 */
object WriterDictionary {

    private const val RESOURCE = "/writer-en-words.txt"

    /**
     * Word to rank, 0 being the most common.
     *
     * The BOM is stripped explicitly. A UTF-8 byte-order mark on the first line turns "the" into
     * "﻿the", which then fails the letters-only filter -- so the single most common word in
     * the language silently vanishes from the dictionary, autocorrect starts "fixing" it, and
     * every test that touches it fails for a reason nothing in the code points at.
     */
    private val ranks: Map<String, Int> by lazy {
        val stream = WriterDictionary::class.java.getResourceAsStream(RESOURCE)
            ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim().removePrefix("﻿").lowercase() }
                .filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }
                .withIndex()
                .associate { (i, w) -> w to i }
        }
    }

    private val byLength: Map<Int, List<String>> by lazy {
        ranks.keys.groupBy { it.length }
    }

    private val userWords = LinkedHashMap<String, Int>()

    val size: Int get() = ranks.size

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return userWords.containsKey(w) || ranks.containsKey(w)
    }

    /**
     * 0 (most common) to 1 (unknown).
     *
     * User words get a rank better than anything built in, so they win ties outright.
     */
    fun score(word: String): Float {
        val w = word.lowercase()
        userWords[w]?.let { return 0f }
        val rank = ranks[w] ?: return 1f
        return rank.toFloat() / ranks.size.coerceAtLeast(1)
    }

    /** Remembers a word the user typed and kept, so it stops being corrected. */
    fun learn(word: String) {
        val w = word.trim().lowercase()
        if (w.length < 2 || !w.all { it in 'a'..'z' }) return
        userWords[w] = (userWords[w] ?: 0) + 1
        // Bounded: a keyboard that grows without limit eventually costs more than it helps.
        if (userWords.size > 2000) {
            userWords.entries.sortedBy { it.value }.take(200).forEach { userWords.remove(it.key) }
        }
    }

    fun userWordList(): List<String> = userWords.keys.toList()

    fun restoreUserWords(words: List<String>) {
        userWords.clear()
        words.forEach { userWords[it.lowercase()] = 1 }
    }

    /** Candidates of a given length, for the swipe decoder to score. */
    fun wordsOfLength(length: Int): List<String> =
        (byLength[length] ?: emptyList()) + userWords.keys.filter { it.length == length }

    fun allWords(): Sequence<String> = ranks.keys.asSequence() + userWords.keys.asSequence()
}
