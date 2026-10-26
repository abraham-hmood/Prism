package com.prism.desktop

import com.prism.desktop.ui.sparkWeights
import com.prism.launcher.DesktopItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spark's sparks are weighted by how often an app is actually launched.
 *
 * The visible claim in the theme is "the most commonly used app shoots the strongest sparks", so
 * the mapping from launch rank to intensity is the part worth a test: it is pure, and getting it
 * backwards would look plausible on screen while meaning the opposite of what the theme says.
 */
class SparkWeightsTest {

    private val cells = listOf<DesktopItem?>(
        DesktopItem.App("com.prism.alpha"),
        DesktopItem.App("com.prism.beta"),
        DesktopItem.App("com.prism.gamma"),
        null,
    )

    @Test
    fun `the most launched app sparks hardest and the least still lights up`() {
        val weights = sparkWeights(
            cells,
            listOf("com.prism.beta", "com.prism.alpha", "com.prism.gamma"),
        )

        assertEquals(1f, weights.getValue(1), 0.0001f)
        assertTrue(
            weights.getValue(0) < weights.getValue(1),
            "the second-most-used app must spark less than the first",
        )
        assertTrue(
            weights.getValue(2) < weights.getValue(0),
            "the third must spark less than the second",
        )
        assertEquals(0.25f, weights.getValue(2), 0.0001f)
        assertNull(weights[3], "an empty cell has nothing to arc from")
    }

    @Test
    fun `an app that has never been launched does not spark`() {
        val weights = sparkWeights(cells, listOf("com.prism.alpha"))
        assertEquals(setOf(0), weights.keys, "only the app with a launch appears")
    }

    @Test
    fun `no usage data means no arcs at all`() {
        assertTrue(sparkWeights(cells, emptyList()).isEmpty())
    }
}
