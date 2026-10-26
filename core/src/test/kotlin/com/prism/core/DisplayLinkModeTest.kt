package com.prism.core

import com.prism.launcher.display.WirelessDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The invert-the-direction rule for a long-press.
 *
 * The direction flips, but the requester's chosen mode does not. These cases are the difference
 * between "long-press sends my screen" and "long-press brings your next page here", and a wrong
 * answer is a blank display rather than an obviously wrong picture.
 */
class DisplayLinkModeTest {

    @Test
    fun `a normal link follows this device's own choice`() {
        assertTrue(WirelessDisplay.resolveExtend(reverse = false, requestedExtend = true, localExtend = false))
        assertFalse(WirelessDisplay.resolveExtend(reverse = false, requestedExtend = false, localExtend = true))
    }

    @Test
    fun `a reverse link follows the requester's choice even against the sender's setting`() {
        assertTrue(WirelessDisplay.resolveExtend(reverse = true, requestedExtend = true, localExtend = false))
        assertFalse(WirelessDisplay.resolveExtend(reverse = true, requestedExtend = false, localExtend = true))
    }

    @Test
    fun `a legacy reverse link remains the mimic it was`() {
        assertFalse(WirelessDisplay.resolveExtend(reverse = true, requestedExtend = null, localExtend = true))
    }

    @Test
    fun `a legacy normal link keeps the sender's own mode`() {
        assertTrue(WirelessDisplay.resolveExtend(reverse = false, requestedExtend = null, localExtend = true))
        assertFalse(WirelessDisplay.resolveExtend(reverse = false, requestedExtend = null, localExtend = false))
    }

    @Test
    fun `the extended page carries the next page's identity, not the current one's`() {
        WirelessDisplay.notePage(
            slots = listOf("default", "app-drawer", "files"),
            index = 1,
            pages = listOf("NORA_CHAT", "DESKTOP", "FILES"),
        )
        val show = WirelessDisplay.displayPage()
        assertEquals(2, show?.index, "extend moves to the page after the current one")
        assertEquals("files", show?.slot, "the slot belongs to that next page")
        assertEquals("FILES", show?.page, "the page identity must move with the page")
    }

    @Test
    fun `the last page extends itself rather than a blank page`() {
        WirelessDisplay.notePage(
            slots = listOf("default", "app-drawer"),
            index = 1,
            pages = listOf("DESKTOP", "APPS"),
        )
        val show = WirelessDisplay.displayPage()
        assertEquals(1, show?.index)
        assertEquals("APPS", show?.page)
    }
}
