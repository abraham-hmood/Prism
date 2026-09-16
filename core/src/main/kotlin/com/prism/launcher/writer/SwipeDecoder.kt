package com.prism.launcher.writer

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns a glide across the keys into words.
 *
 * ## The idea
 *
 * A swipe is a path, and a word is also a path -- the one you would trace going key to key through
 * its letters. Decoding is therefore a shape-matching problem: resample both to the same number of
 * points and measure how far apart they are. That handles the things a naive approach gets wrong,
 * like a user cutting corners, drifting between keys, or gliding faster in the middle than at the
 * ends.
 *
 * ## Why candidates are filtered before they are scored
 *
 * Comparing every dictionary word against every swipe is far too slow for something that has to
 * respond between one gesture and the next. Two cheap tests remove almost everything first:
 *
 *   FIRST AND LAST LETTER. A glide starts on the first letter and ends on the last -- those are
 *   the two points a user is most deliberate about, and they are nearly always right.
 *
 *   LETTERS IN ORDER. Every letter of the word must appear along the path in sequence. This is
 *   what separates "the" from "tie" without measuring anything.
 *
 * What survives both is a handful of words, and those get the real shape comparison.
 */
object SwipeDecoder {

    /** How many points both paths are resampled to before comparison. */
    private const val SAMPLES = 24

    /** Path points closer together than this are dropped as jitter. */
    private const val MIN_STEP = 0.012f

    data class Point(val x: Float, val y: Float)

    data class Candidate(val word: String, val score: Float)

    /**
     * Best guesses for a gesture, most likely first.
     *
     * Returns empty for anything too short to be a word -- a tap that wandered is a tap, and
     * turning it into a five-letter word is worse than doing nothing.
     */
    fun decode(
        path: List<Point>,
        layout: KeyboardLayout,
        limit: Int = 4,
    ): List<Candidate> {
        val clean = simplify(path)
        if (clean.size < 3) return emptyList()
        if (pathLength(clean) < 0.15f) return emptyList()

        val first = layout.nearestLetter(clean.first().x, clean.first().y) ?: return emptyList()
        val last = layout.nearestLetter(clean.last().x, clean.last().y) ?: return emptyList()

        val touched = lettersAlongPath(clean, layout)
        val resampled = resample(clean, SAMPLES)

        val scored = ArrayList<Candidate>()
        for (word in WriterDictionary.allWords()) {
            if (word.length < 2) continue
            if (word.first() != first.output[0].lowercaseChar()) continue
            if (word.last() != last.output[0].lowercaseChar()) continue
            // Compared with repeats collapsed on BOTH sides. A double letter is one key, so
            // gliding "hello" traces h-e-l-o -- the finger cannot visit the same key twice
            // without leaving it. Matching the raw word would reject every double-letter word
            // in the language, which is a lot of them.
            if (!isSubsequence(collapseRepeats(word), touched)) continue

            val ideal = idealPath(collapseRepeats(word), layout) ?: continue
            val shape = shapeDistance(resampled, resample(ideal, SAMPLES))

            // Frequency breaks ties that geometry cannot: two words with near-identical paths are
            // resolved by which one people actually write.
            val score = shape + WriterDictionary.score(word) * 0.35f
            scored.add(Candidate(word, score))
        }

        return scored.sortedBy { it.score }.take(limit)
    }

    /** Drops points too close together to carry information. */
    fun simplify(path: List<Point>): List<Point> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<Point>(path.size)
        out.add(path.first())
        for (p in path.drop(1)) {
            val last = out.last()
            if (distance(last, p) >= MIN_STEP) out.add(p)
        }
        if (out.size == 1 && path.size > 1) out.add(path.last())
        return out
    }

    /**
     * The letters the path actually crosses, in order and without repeats.
     *
     * Consecutive duplicates are collapsed because dwelling on a key -- which everyone does at a
     * direction change -- would otherwise read as a doubled letter and reject the right word.
     */
    fun lettersAlongPath(path: List<Point>, layout: KeyboardLayout): String {
        val sb = StringBuilder()
        for (p in path) {
            val key = layout.nearestLetter(p.x, p.y) ?: continue
            val c = key.output[0].lowercaseChar()
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Collapses consecutive duplicate letters: "hello" becomes "helo".
     *
     * A glide cannot express a doubled letter -- both are the same key -- so words are compared in
     * this reduced form. The full word is still what gets inserted; only the matching is reduced.
     */
    fun collapseRepeats(word: String): String {
        if (word.isEmpty()) return word
        val sb = StringBuilder(word.length)
        for (c in word) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString()
    }

    /** Whether every letter of [word] appears in [sequence], in order. */
    fun isSubsequence(word: String, sequence: String): Boolean {
        var i = 0
        for (c in sequence) {
            if (i < word.length && word[i] == c) i++
        }
        return i == word.length
    }

    /** The path a perfect glide through this word would trace. */
    fun idealPath(word: String, layout: KeyboardLayout): List<Point>? {
        val points = ArrayList<Point>(word.length)
        for (c in word) {
            val key = layout.letterKey(c) ?: return null
            points.add(Point(key.centerX, key.centerY))
        }
        return if (points.size >= 2) points else null
    }

    /**
     * Resamples to a fixed number of evenly spaced points.
     *
     * THIS IS WHAT MAKES SPEED IRRELEVANT. Raw touch samples cluster wherever the finger slowed
     * down, so comparing them directly would score a careful writer and a fast one differently for
     * the same word. Even spacing compares shape alone.
     */
    fun resample(path: List<Point>, count: Int): List<Point> {
        if (path.size < 2 || count < 2) return path
        val total = pathLength(path)
        if (total <= 0f) return List(count) { path.first() }

        val step = total / (count - 1)
        val out = ArrayList<Point>(count)
        out.add(path.first())

        var walked = 0f
        var index = 1
        var current = path.first()
        while (out.size < count && index < path.size) {
            val next = path[index]
            val segment = distance(current, next)
            if (segment <= 0f) { index++; continue }

            if (walked + segment >= step) {
                val t = (step - walked) / segment
                val interpolated = Point(
                    current.x + (next.x - current.x) * t,
                    current.y + (next.y - current.y) * t,
                )
                out.add(interpolated)
                current = interpolated
                walked = 0f
            } else {
                walked += segment
                current = next
                index++
            }
        }
        while (out.size < count) out.add(path.last())
        return out
    }

    /** Mean point-to-point distance between two equally sampled paths. */
    fun shapeDistance(a: List<Point>, b: List<Point>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val n = minOf(a.size, b.size)
        var sum = 0f
        for (i in 0 until n) sum += distance(a[i], b[i])
        return sum / n
    }

    fun pathLength(path: List<Point>): Float {
        var total = 0f
        for (i in 1 until path.size) total += distance(path[i - 1], path[i])
        return total
    }

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** True when a gesture is long enough to be a word rather than a slipped tap. */
    fun isGesture(path: List<Point>): Boolean = pathLength(simplify(path)) >= 0.15f
}
