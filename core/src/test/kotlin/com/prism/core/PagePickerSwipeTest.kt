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

    @Test
    fun `a diagonal is not reduced to one axis`() {
        val trace = listOf(
            PagePickerSwipe.TracePoint(0f, 0f),
            PagePickerSwipe.TracePoint(60f, 60f),
            PagePickerSwipe.TracePoint(140f, 140f),
        )
        val trigger = PagePickerSwipe.fromTraceSamples(
            listOf(trace, trace, trace).mapIndexed { index, points ->
                PagePickerSwipe.SwipeSample(
                    dx = points.last().x,
                    dy = points.last().y,
                    pressure = 0.7f,
                    trace = points,
                )
            },
            density,
        )

        assertNotNull(trigger)
        assertEquals(PagePickerSwipe.Shape.DIAGONAL, trigger.shape)
        assertTrue(trigger.label.contains("down"))
        assertFalse(trigger.edgeOnly)
        assertTrue(
            trigger.matchesTrace(
                listOf(
                    PagePickerSwipe.TracePoint(0f, 0f),
                    PagePickerSwipe.TracePoint(80f, 80f),
                    PagePickerSwipe.TracePoint(180f, 180f),
                ),
                0.7f,
                density,
            ),
        )
    }

    @Test
    fun `a loop is captured as a custom shape and needs its pressure`() {
        val loop = (0..24).map { step ->
            val angle = Math.PI * 2 * step / 24
            PagePickerSwipe.TracePoint(
                x = (200 * Math.sin(angle)).toFloat(),
                y = (200 - 200 * Math.cos(angle)).toFloat() - 200f,
            )
        }
        val samples = listOf(loop, loop, loop).map { points ->
            PagePickerSwipe.SwipeSample(
                dx = points.last().x,
                dy = points.last().y,
                pressure = 0.8f,
                trace = points,
            )
        }
        val trigger = assertNotNull(PagePickerSwipe.fromTraceSamples(samples, density))
        assertEquals(PagePickerSwipe.Shape.CUSTOM, trigger.shape)
        assertEquals(0.8f, trigger.pressure!!, 0.02f)

        assertTrue(trigger.matchesTrace(loop, 0.8f, density))
        // A much lighter press is not the gesture that was taught, even though the path matches.
        assertFalse(trigger.matchesTrace(loop, 0.2f, density))
        // A plain line is not the loop either.
        assertFalse(
            trigger.matchesTrace(
                listOf(
                    PagePickerSwipe.TracePoint(0f, 0f),
                    PagePickerSwipe.TracePoint(0f, -180f),
                    PagePickerSwipe.TracePoint(0f, -360f),
                ),
                0.8f,
                density,
            ),
        )
    }

    @Test
    fun `a captured path survives serialization`() {
        val path = (0..8).map { step ->
            PagePickerSwipe.TracePoint(step * 0.1f, -step * 0.08f)
        }
        val original = PagePickerSwipe(
            direction = PagePickerSwipe.Direction.UP,
            distanceDp = 70,
            edgeOnly = false,
            path = PagePickerSwipe.normalise(path)!!,
            pressure = 0.63f,
            shape = PagePickerSwipe.Shape.DIAGONAL,
        )

        val decoded = assertNotNull(PagePickerSwipe.deserialize(original.toJson()))
        assertEquals(original.direction, decoded.direction)
        assertEquals(original.distanceDp, decoded.distanceDp)
        assertEquals(original.pressure, decoded.pressure)
        assertEquals(original.shape, decoded.shape)
        assertEquals(original.path.size, decoded.path.size)
        assertEquals(original.path.first().x, decoded.path.first().x, 0.0001f)
        assertEquals(original.path.last().y, decoded.path.last().y, 0.001f)
    }
}
