package com.prism.launcher.virtualization

import android.view.KeyEvent

/**
 * Android key codes to X11 keysyms, which is what RFB speaks.
 *
 * THE TWO NUMBERING SCHEMES SHARE NOTHING. Android's `KEYCODE_A` is 29; X11's `XK_a` is 0x61.
 * Sending an Android keycode straight down an RFB connection types whatever character happens to
 * live at that keysym -- for 29 that is a control character, so the guest sees garbage and the
 * login prompt rejects a password that looked correct on screen.
 *
 * PRINTABLE CHARACTERS ARE MAPPED FROM THE UNICODE VALUE, NOT THE KEYCODE. X11 keysyms for Latin-1
 * are numerically equal to their Unicode code points, which means the soft keyboard's own character
 * output can be used directly -- and that is the only approach that survives shift, long-press
 * accents, and alternative keyboard layouts. Mapping keycodes by hand would produce a US-layout
 * guest no matter what the user is actually typing on.
 */
internal object VncKeysyms {

    // Modifiers.
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9

    // Control and navigation keys, which have no printable character to derive from.
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63

    /** F1..F12 are contiguous from 0xFFBE. */
    fun functionKey(number: Int): Int = 0xFFBE + (number - 1).coerceIn(0, 11)

    /**
     * The keysym for a key press, or null when the caller should fall back to the typed character.
     *
     * Only keys WITHOUT a printable character are handled here. Everything else deliberately falls
     * through to [forCharacter], because the character the keyboard produced already accounts for
     * shift state and layout in a way a keycode never could.
     */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            functionKey(keyCode - KeyEvent.KEYCODE_F1 + 1)
        else -> null
    }

    /**
     * The keysym for a printable character.
     *
     * Latin-1 maps one-to-one onto keysyms, so ASCII and the accented range go straight through.
     * Anything above that uses the Unicode keysym range (0x01000000 + code point), which every VNC
     * server since RFB 3.8 understands -- that is what makes non-Latin input work at all.
     */
    fun forCharacter(c: Char): Int? = when {
        c.code == 0 -> null

        // CONTROL CHARACTERS ARE NOT LATIN-1 KEYSYMS, and this is the trap. A keyboard that
        // commits Enter as TEXT rather than as a key event hands over a newline, whose code is 10
        // -- and keysym 10 is a linefeed that no X11 keymap binds to anything, so the guest
        // silently ignores it. Return is 0xFF0D. The same applies to tab and backspace.
        //
        // This is what makes Enter work regardless of which route the IME takes: a raw key event,
        // performEditorAction, or committing a newline like any other character.
        c.code == 10 || c.code == 13 -> RETURN
        c.code == 9 -> TAB
        c.code == 8 -> BACKSPACE
        c.code < 0x20 -> null                    // no other control code maps to a key

        c.code < 0x100 -> c.code
        else -> 0x01000000 + c.code
    }
}
