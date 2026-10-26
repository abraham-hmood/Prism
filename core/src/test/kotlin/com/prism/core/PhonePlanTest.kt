package com.prism.core

import com.prism.launcher.calls.PrismPhonePlan
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan object has no telephony of its own; the platform installs a probe. This pins that the
 * probe is what the rest of Prism reads, and that the fallback is an honest "no plan" rather than a
 * guessed one.
 */
class PhonePlanTest {

    private var previous: (() -> PrismPhonePlan.Status)? = null

    @BeforeTest
    fun rememberProbe() {
        previous = PrismPhonePlan.probe
    }

    @AfterTest
    fun restoreProbe() {
        PrismPhonePlan.probe = previous
        PrismPhonePlan.refresh()
    }

    @Test
    fun `an active probe is reported as an active plan`() {
        PrismPhonePlan.probe = {
            PrismPhonePlan.Status(true, number = "+15551234567", carrier = "Test Carrier")
        }
        PrismPhonePlan.refresh()

        val status = PrismPhonePlan.status()
        assertTrue(status.active)
        assertEquals("+15551234567", status.number)
        assertEquals("Test Carrier", status.carrier)
    }

    @Test
    fun `without a probe the answer is no plan`() {
        PrismPhonePlan.probe = null
        PrismPhonePlan.refresh()

        val status = PrismPhonePlan.status()
        assertFalse(status.active)
        assertTrue(status.reason.isNotBlank())
    }
}
