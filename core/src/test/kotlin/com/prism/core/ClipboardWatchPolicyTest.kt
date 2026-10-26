package com.prism.core

import com.prism.launcher.clipboard.ClipboardWatchPolicy
import com.prism.launcher.clipboard.ClipboardWatchPolicy.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardWatchPolicyTest {

    private val own = "com.prism.launcher"

    @Test
    fun `off is off whatever the keyboard is`() {
        assertEquals(
            State.OFF,
            ClipboardWatchPolicy.state(false, "$own/.writer.PrismWriterService", own),
        )
        assertEquals(
            State.OFF,
            ClipboardWatchPolicy.state(false, "com.example.other/.Ime", own),
        )
    }

    @Test
    fun `our own keyboard in the background is live`() {
        assertEquals(
            State.LIVE,
            ClipboardWatchPolicy.state(true, "$own/.writer.PrismWriterService", own),
        )
    }

    @Test
    fun `someone else's keyboard needs ours`() {
        assertEquals(
            State.NEEDS_KEYBOARD,
            ClipboardWatchPolicy.state(true, "com.example.other/com.example.other.Ime", own),
        )
    }

    @Test
    fun `no keyboard selected needs ours rather than silently reading nothing`() {
        assertEquals(State.NEEDS_KEYBOARD, ClipboardWatchPolicy.state(true, null, own))
        assertEquals(State.NEEDS_KEYBOARD, ClipboardWatchPolicy.state(true, "   ", own))
    }

    @Test
    fun `a blank own package can never be live`() {
        assertEquals(State.OFF, ClipboardWatchPolicy.state(true, "$own/.Ime", ""))
    }

    @Test
    fun `component parsing keeps only the package`() {
        assertEquals("com.prism.launcher", ClipboardWatchPolicy.packageOf("com.prism.launcher/.writer.PrismWriterService"))
        assertEquals("com.prism.launcher", ClipboardWatchPolicy.packageOf("  com.prism.launcher  "))
        assertEquals("com.example.other", ClipboardWatchPolicy.packageOf("com.example.other/com.example.other.Ime"))
        assertNull(ClipboardWatchPolicy.packageOf(null))
        assertNull(ClipboardWatchPolicy.packageOf(""))
        assertNull(ClipboardWatchPolicy.packageOf("  / .Ime"))
    }
}
