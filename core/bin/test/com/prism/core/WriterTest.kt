package com.prism.core

import com.prism.launcher.writer.Autocorrect
import com.prism.launcher.writer.Key
import com.prism.launcher.writer.KeyboardLayout
import com.prism.launcher.writer.SwipeDecoder
import com.prism.launcher.writer.WriterDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The typing logic behind Prism Writer.
 *
 * THE TWO FAILURES THAT MATTER ARE OPPOSITE ONES. A keyboard that never corrects is merely
 * unhelpful; a keyboard that corrects words the user meant is actively hostile, and it is the
 * reason people turn autocorrect off. So the tests cover both directions -- real typos are fixed,
 * and correct words are left alone.
 */
class WriterTest {

    private val layout = KeyboardLayout.qwerty()

    // ── Layout ─────────────────────────────────────────────────────────────

    @Test
    fun `layout covers the alphabet and stays inside its bounds`() {
        val letters = layout.keys.filter { it.isLetter }.map { it.output[0] }.toSet()
        assertEquals(26, letters.size, "every letter must have a key")

        for (key in layout.keys) {
            assertTrue(key.centerX - key.width / 2 >= -0.01f, "${key.label} runs off the left")
            assertTrue(key.centerX + key.width / 2 <= 1.01f, "${key.label} runs off the right")
            assertTrue(key.centerY in 0f..1f, "${key.label} is outside vertically")
        }
    }

    @Test
    fun `keys do not overlap within a row`() {
        for (row in layout.rows) {
            val sorted = row.sortedBy { it.centerX }
            for (i in 1 until sorted.size) {
                val left = sorted[i - 1]
                val right = sorted[i]
                assertTrue(
                    left.centerX + left.width / 2 <= right.centerX - right.width / 2 + 0.001f,
                    "${left.label} overlaps ${right.label}",
                )
            }
        }
    }

    @Test
    fun `neighbouring letters are closer than distant ones`() {
        assertTrue(layout.letterDistance('a', 's') < layout.letterDistance('a', 'p'))
        assertTrue(layout.letterDistance('q', 'w') < layout.letterDistance('q', 'm'))
        assertEquals(0f, layout.letterDistance('a', 'a'))
    }

    @Test
    fun `translate key appears only when asked for`() {
        val without = KeyboardLayout.qwerty(showTranslate = false)
        val with = KeyboardLayout.qwerty(showTranslate = true)
        assertTrue(without.keys.none { it.action == Key.Action.TRANSLATE })
        assertTrue(with.keys.any { it.action == Key.Action.TRANSLATE })
    }

    @Test
    fun `shift changes what letters produce`() {
        val lower = KeyboardLayout.qwerty(shifted = false)
        val upper = KeyboardLayout.qwerty(shifted = true)
        assertEquals("q", lower.letterKey('q')?.output)
        assertEquals("Q", upper.keys.first { it.label == "Q" }.output)
    }

    // ── Swipe decoding ─────────────────────────────────────────────────────

    /** Builds the path a perfect glide through a word would trace. */
    private fun glide(word: String, samplesPerLeg: Int = 12): List<SwipeDecoder.Point> {
        val keys = word.map { layout.letterKey(it)!! }
        val out = ArrayList<SwipeDecoder.Point>()
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            for (s in 0 until samplesPerLeg) {
                val t = s.toFloat() / samplesPerLeg
                out.add(
                    SwipeDecoder.Point(
                        a.centerX + (b.centerX - a.centerX) * t,
                        a.centerY + (b.centerY - a.centerY) * t,
                    )
                )
            }
        }
        out.add(SwipeDecoder.Point(keys.last().centerX, keys.last().centerY))
        return out
    }

    @Test
    fun `a clean glide decodes to its word`() {
        for (word in listOf("hello", "world", "keyboard", "computer")) {
            val results = SwipeDecoder.decode(glide(word), layout)
            assertTrue(results.isNotEmpty(), "no candidates for '$word'")
            assertTrue(
                results.take(3).any { it.word == word },
                "'$word' not in top 3: ${results.map { it.word }}",
            )
        }
    }

    /** A wobbly finger must still land on the word -- shape matters, not precision. */
    @Test
    fun `a noisy glide still decodes`() {
        val random = java.util.Random(42)
        val noisy = glide("hello").map {
            SwipeDecoder.Point(
                it.x + (random.nextFloat() - 0.5f) * 0.03f,
                it.y + (random.nextFloat() - 0.5f) * 0.03f,
            )
        }
        val results = SwipeDecoder.decode(noisy, layout)
        assertTrue(results.take(3).any { it.word == "hello" }, "got ${results.map { it.word }}")
    }

    @Test
    fun `a tap is not treated as a gesture`() {
        val tap = listOf(
            SwipeDecoder.Point(0.3f, 0.3f),
            SwipeDecoder.Point(0.305f, 0.302f),
        )
        assertTrue(SwipeDecoder.decode(tap, layout).isEmpty())
        assertTrue(!SwipeDecoder.isGesture(tap))
        assertTrue(SwipeDecoder.isGesture(glide("hello")))
    }

    @Test
    fun `resampling makes speed irrelevant`() {
        val slow = glide("world", samplesPerLeg = 30)
        val fast = glide("world", samplesPerLeg = 4)
        val a = SwipeDecoder.resample(SwipeDecoder.simplify(slow), 24)
        val b = SwipeDecoder.resample(SwipeDecoder.simplify(fast), 24)
        assertTrue(
            SwipeDecoder.shapeDistance(a, b) < 0.02f,
            "the same shape traced at different speeds should match",
        )
    }

    @Test
    fun `subsequence matching collapses dwelling on a key`() {
        assertTrue(SwipeDecoder.isSubsequence("the", "tghe"))
        assertTrue(SwipeDecoder.isSubsequence("hi", "hji"))
        assertTrue(!SwipeDecoder.isSubsequence("the", "eht"))
    }

    // ── Autocorrect ────────────────────────────────────────────────────────

    @Test
    fun `common typos are corrected`() {
        val cases = mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "wrold" to "world",
        )
        for ((typo, expected) in cases) {
            val fixed = Autocorrect.correct(typo, layout)
            assertEquals(expected, fixed, "'$typo' should become '$expected'")
        }
    }

    /** The half that stops people disabling autocorrect. */
    @Test
    fun `real words are never corrected`() {
        for (word in listOf("the", "hello", "world", "computer", "keyboard", "system")) {
            assertNull(Autocorrect.correct(word, layout), "'$word' must be left alone")
        }
    }

    @Test
    fun `very short words are left alone`() {
        assertNull(Autocorrect.correct("zx", layout))
        assertNull(Autocorrect.correct("q", layout))
    }

    @Test
    fun `nonsense is not forced into a word`() {
        assertNull(Autocorrect.correct("zxqwvbn", layout))
    }

    @Test
    fun `key distance makes neighbouring slips cheaper`() {
        val near = Autocorrect.weightedDistance("wotld", "world", layout)
        val far = Autocorrect.weightedDistance("wozld", "world", layout)
        assertTrue(near < far, "a neighbouring slip should cost less ($near vs $far)")
    }

    @Test
    fun `capitalisation survives correction`() {
        assertEquals("The", Autocorrect.correct("Teh", layout))
        assertEquals("THE", Autocorrect.correct("TEH", layout))
        assertEquals("the", Autocorrect.correct("teh", layout))
    }

    // ── Dictionary ─────────────────────────────────────────────────────────

    @Test
    fun `dictionary loads and ranks by frequency`() {
        assertTrue(WriterDictionary.size > 5000, "expected a real word list")
        assertTrue(WriterDictionary.contains("the"))
        assertTrue(WriterDictionary.contains("keyboard"))
        assertTrue(!WriterDictionary.contains("zxqwv"))
        assertTrue(WriterDictionary.score("the") < WriterDictionary.score("poison"))
    }

    @Test
    fun `learned words stop being corrected`() {
        val name = "prismcoin"
        WriterDictionary.learn(name)
        assertTrue(WriterDictionary.contains(name))
        assertNull(Autocorrect.correct(name, layout), "a learned word must be left alone")
    }
}
