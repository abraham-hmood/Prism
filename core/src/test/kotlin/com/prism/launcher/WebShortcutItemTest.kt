package com.prism.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The browser's desktop shortcuts, as stored in the desktop grid. */
class WebShortcutItemTest {

    @Test
    fun `a shortcut survives the grid's own separators`() {
        // "|" splits an item and ";;" splits the grid; a URL may contain both.
        val item = DesktopItem.WebShortcut(
            "https://example.com/a|b;;c?q=1&r=%20x#frag",
            "Title | with ;; separators — and ünïcode",
        )
        val raw = item.serialize()
        assertFalse(raw.contains(";;"), "the grid would split this cell in two: $raw")
        assertEquals(item, DesktopItem.deserialize(raw))
    }

    @Test
    fun `only web addresses come back as shortcuts`() {
        val raw = DesktopItem.WebShortcut("javascript:alert(1)", "x").serialize()
        assertNull(DesktopItem.deserialize(raw))
    }

    @Test
    fun `a missing title falls back to the address`() {
        val item = DesktopItem.deserialize("web|https%3A%2F%2Fexample.com%2F")
        assertEquals(DesktopItem.WebShortcut("https://example.com/", "https://example.com/"), item)
    }
}
