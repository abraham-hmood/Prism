package com.prism.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The heat/memory policy, without a phone.
 *
 * The point of these tests is the SHAPE of the policy rather than the exact numbers: a cold phone
 * must get everything it asks for, a dying one must get one thread and a duty cycle, and the
 * decisions must be monotonic -- more heat can never mean more work. That last property is what
 * stops a governor from oscillating, which is worse than not having one.
 */
class PrismSustainTest {

    @BeforeTest
    fun setUp() {
        PrismPower.thermalStatus = 0
        PrismPower.thermalHeadroom = 0f
        PrismPower.charging = true
        PrismPower.powerSave = false
        PrismSustain.memoryPressure = 0
    }

    @AfterTest
    fun cleanUp() = setUp()

    @Test
    fun `a cool phone gets the whole thread budget`() {
        assertEquals(4, PrismSustain.inferenceThreads(4))
        assertEquals(0L, PrismSustain.interTokenPauseMillis())
        assertFalse(PrismSustain.dutyCycle().isDutyCycled)
        assertTrue(PrismSustain.backgroundWorkAllowed())
    }

    @Test
    fun `threads only ever fall as the device heats up`() {
        var previous = PrismSustain.inferenceThreads(8)
        for (status in 1..5) {
            PrismPower.thermalStatus = status
            val current = PrismSustain.inferenceThreads(8)
            assertTrue(current <= previous, "status $status raised the budget: $previous -> $current")
            assertTrue(current >= 1, "status $status left no thread to finish on")
            previous = current
        }
        assertEquals(1, PrismSustain.inferenceThreads(8), "critical has to mean one thread")
    }

    @Test
    fun `duty cycle and inter-token pauses only appear when hot`() {
        assertEquals(0L, PrismSustain.interTokenPauseMillis())
        PrismPower.thermalStatus = 2
        assertTrue(PrismSustain.interTokenPauseMillis() > 0L)
        assertTrue(PrismSustain.dutyCycle().isDutyCycled)
        PrismPower.thermalStatus = 4
        assertTrue(
            PrismSustain.dutyCycle().workFraction < 0.5f,
            "at critical the phone has to spend most of its time cooling",
        )
    }

    @Test
    fun `a single headroom reading sheds one thread before the platform throttles`() {
        assertEquals(4, PrismSustain.inferenceThreads(4))
        PrismPower.thermalHeadroom = 0.8f
        assertTrue(PrismSustain.nearThrottle)
        assertEquals(3, PrismSustain.inferenceThreads(4))
        // ...but not below two: a warm phone is not a dying one.
        assertEquals(2, PrismSustain.inferenceThreads(2))
    }

    @Test
    fun `memory pressure caps threads and defers background work`() {
        PrismSustain.memoryPressure = 2
        assertEquals(2, PrismSustain.inferenceThreads(8))
        assertFalse(PrismSustain.backgroundWorkAllowed(), "a low-memory process must not start a crawl")
        assertTrue(PrismSustain.shouldTrimCaches())

        PrismSustain.memoryPressure = 3
        assertEquals(1, PrismSustain.inferenceThreads(8))
    }

    @Test
    fun `background work needs a charger and a cool device`() {
        assertTrue(PrismSustain.backgroundWorkAllowed())
        PrismPower.charging = false
        assertFalse(PrismSustain.backgroundWorkAllowed())
        PrismPower.charging = true
        PrismPower.powerSave = true
        assertFalse(PrismSustain.backgroundWorkAllowed())
        PrismPower.powerSave = false
        PrismPower.thermalStatus = 3
        assertFalse(PrismSustain.backgroundWorkAllowed())
    }
}
