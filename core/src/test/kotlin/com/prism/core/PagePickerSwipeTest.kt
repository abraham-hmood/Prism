package com.prism.core

import com.prism.launcher.PagePickerSwipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page-picker gesture is captured from three swipes and then has to recognise the same gesture
 * later. Both halves are arithmetic, so both are tested here rather than left to a device.
 */
class PagePickerSwipeTest {

    private val density = 2.5f

    @Test
    fun `three matching swipes become one trigger`() {
        val trigger = PagePickerSwipe.fromSamples(
            listOf(
                PagePickerSwipe.SwipeSample(0f, -260f),
                PagePickerSwipe.SwipeSample(12f, -300f),
                PagePickerSwipe.SwipeSample(-8f, -280f),
            ),
            density,
        )

        assertNotNull(trigger)
        assertEquals(PagePickerSwipe.Direction.UP, trigger.direction)
        assertTrue(trigger.distanceDp in 24..200)
        assertFalse(trigger.edgeOnly)
    }

    @Test
    fun `a different direction restarts rather than producing a trigger`() {
        assertNull(
            PagePickerSwipe.fromSamples(
                listOf(
                    PagePickerSwipe.SwipeSample(0f, -260f),
                    PagePickerSwipe.SwipeSample(0f, -280f),
                    PagePickerSwipe.SwipeSample(260f, 0f),
                ),
                density,
            )
        )
    }

    @Test
    fun `too short a sample is refused`() {
        assertNull(
            PagePickerSwipe.fromSamples(
                listOf(
                    PagePickerSwipe.SwipeSample(0f, -5f),
                    PagePickerSwipe.SwipeSample(0f, -6f),
                    PagePickerSwipe.SwipeSample(0f, -5f),
                ),
                density,
            )
        )
    }

    @Test
    fun `matching is in dp and respects the dominant axis`() {
        val trigger = PagePickerSwipe(PagePickerSwipe.Direction.UP, 80)

        assertTrue(trigger.matches(0f, -80f * density, density))
        assertFalse(trigger.matches(0f, -70f * density, density))
        // A mostly horizontal gesture must not count as an upward swipe.
        assertFalse(trigger.matches(400f, -90f * density, density))
    }

    @Test
    fun `horizontal triggers are edge only`() {
        val trigger = PagePickerSwipe(PagePickerSwipe.Direction.LEFT, 60, edgeOnly = true)

        assertTrue(trigger.isEligibleStart(startX = 1060f, width = 1080, density = 2f))
        assertFalse(trigger.isEligibleStart(startX = 400f, width = 1080, density = 2f))
    }

    @Test
    fun `lower sensitivity stretches the same trigger`() {
        val trigger = PagePickerSwipe(PagePickerSwipe.Direction.UP, 80)
        val scale = PagePickerSwipe.sensitivityScale(25)

        // The old threshold still counts; a deliberate shorter flick does not.
        assertTrue(trigger.matches(0f, -80f * density * scale, density, scale))
        assertFalse(trigger.matches(0f, -80f * density * 1.4f, density, scale))
    }

    @Test
    fun `sensitivity scale is one at full and larger below it`() {
        assertEquals(1f, PagePickerSwipe.sensitivityScale(100))
        assertTrue(PagePickerSwipe.sensitivityScale(0) > PagePickerSwipe.sensitivityScale(50))
        assertTrue(PagePickerSwipe.sensitivityScale(50) > PagePickerSwipe.sensitivityScale(100))
    }

    @Test
    fun `serialization round trips and rejects nonsense`() {
        val original = PagePickerSwipe(PagePickerSwipe.Direction.DOWN, 92, edgeOnly = false)
        assertEquals(original, PagePickerSwipe.deserialize(original.toJson()))
        assertNull(PagePickerSwipe.deserialize(""))
        assertNull(PagePickerSwipe.deserialize("SIDEWAYS|80|true"))
        assertNull(PagePickerSwipe.deserialize("UP|not-a-number|true"))
    }
}
