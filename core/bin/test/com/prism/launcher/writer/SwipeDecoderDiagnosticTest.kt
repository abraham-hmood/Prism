package com.prism.launcher.writer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces a glide on the JVM, to find out why swiping types nothing on the device.
 *
 * Written as a test rather than as device poking because every part of the decode path is pure:
 * the layout is fractional, the decoder takes a list of points, and the dictionary is a resource.
 * Nothing about "swipe does not work" needs a phone to diagnose.
 */
class SwipeDecoderDiagnosticTest {

    /** A straight glide through the centres of each letter, which is the ideal case. */
    private fun pathThrough(word: String, layout: KeyboardLayout): List<SwipeDecoder.Point> {
        val centres = word.mapNotNull { layout.letterKey(it) }.map { it.centerX to it.centerY }
        val points = mutableListOf<SwipeDecoder.Point>()
        for (i in 0 until centres.size - 1) {
            val (x1, y1) = centres[i]
            val (x2, y2) = centres[i + 1]
            // 12 samples a segment, which is roughly what a finger produces at 60 Hz.
            for (step in 0 until 12) {
                val t = step / 12f
                points.add(SwipeDecoder.Point(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
            }
        }
        centres.lastOrNull()?.let { points.add(SwipeDecoder.Point(it.first, it.second)) }
        return points
    }

    @Test
    fun `dictionary actually loaded`() {
        println("dictionary size = ${WriterDictionary.size}")
        assertTrue(WriterDictionary.size > 100, "dictionary is empty or tiny: ${WriterDictionary.size}")
        assertTrue(WriterDictionary.contains("the"), "'the' missing from the dictionary")
    }

    @Test
    fun `a clean glide decodes to the word`() {
        val layout = KeyboardLayout.qwerty()
        for (word in listOf("the", "hello", "world", "keyboard", "prism")) {
            val path = pathThrough(word, layout)
            val isGesture = SwipeDecoder.isGesture(path)
            val candidates = SwipeDecoder.decode(path, layout)
            println(
                "%-9s points=%-3d isGesture=%-5s candidates=%s"
                    .format(word, path.size, isGesture, candidates.take(4).map { it.word })
            )
        }

        // "hello" spans most of the keyboard, so if anything decodes it should.
        val hello = SwipeDecoder.decode(pathThrough("hello", layout), layout)
        assertTrue(hello.isNotEmpty(), "a perfect glide through h-e-l-l-o decoded to nothing")
    }
}
