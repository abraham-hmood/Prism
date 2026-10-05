package com.prism.desktop.writer

import com.prism.core.PrismPlatform
import java.awt.Robot
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

/**
 * Getting typed characters out of Prism and into whatever has focus. PHASE 102.
 *
 * ## WHY THIS EXISTS AT ALL, GIVEN WHAT THE PLAN SAID
 *
 * The plan said: "ALMOST CERTAINLY DO NOT PORT THE KEYBOARD. A desktop has a hardware keyboard and
 * no IME framework an application can plug into the way Android's `InputMethodService` allows --
 * Windows TSF and Linux IBus/Fcitx are both system-level components installed separately, not
 * something a launcher provides."
 *
 * That was overruled on instruction: Prism Writer is to be a virtual keyboard on the desktop, with
 * the same style and features as the phone's. The plan's technical point still stands and is worth
 * being precise about rather than quietly contradicting:
 *
 *  - Prism is NOT registered as a system input method. It cannot be, without shipping a TSF text
 *    service or an IBus engine and having the user install it.
 *  - What it is instead is an ON-SCREEN KEYBOARD: a panel that synthesises keystrokes into whatever
 *    window has focus, the way every accessibility keyboard and every tablet keyboard does.
 *
 * The difference that matters to a user is that the panel does not pop up automatically when a text
 * field is focused -- nothing tells Prism that happened -- and that is the one capability lost. The
 * suggestions, the learned dictionary, the swipe decoder, the emoji and GIF panels and the inline
 * search all work exactly as they do on the phone, because all of them are in `:core`.
 *
 * ## `Robot` rather than platform text injection
 *
 * `java.awt.Robot` synthesises OS-level key events. The alternatives were `SendInput` through JNA on
 * Windows and `XTestFakeKeyEvent` on Linux -- two bindings for what the JDK already does on both,
 * and `Robot` is what every Java accessibility tool uses.
 *
 * ## THE CLIPBOARD IS THE PATH FOR ANYTHING NOT ON A US KEYBOARD, AND THAT IS NOT LAZINESS
 *
 * `Robot.keyPress` takes a VIRTUAL KEY CODE, not a character. There is no reliable mapping from an
 * arbitrary Unicode character to a key code: `KeyEvent.getExtendedKeyCodeForChar` answers for ASCII
 * and for some Latin-1, and returns `VK_UNDEFINED` for an emoji, a CJK character, or anything on a
 * layout the user does not have. Pressing a key code that is not on the physical layout types the
 * WRONG CHARACTER rather than failing.
 *
 * So anything outside the directly typeable set goes through the clipboard and Ctrl+V. The previous
 * clipboard contents are saved and restored, because silently eating what somebody had copied would
 * be a worse bug than a keyboard that cannot type an emoji.
 */
object DesktopTypist {

    private const val TAG = "PrismWriter"

    private val robot: Robot? by lazy {
        runCatching { Robot().apply { autoDelay = 2 } }.getOrElse {
            PrismPlatform.log.error(TAG, "AWT would not give Prism a Robot", it)
            null
        }
    }

    fun isAvailable(): Boolean = robot != null

    fun unavailableReason(): String = when {
        robot != null -> ""
        java.awt.GraphicsEnvironment.isHeadless() ->
            "This session is headless, so there is no window to type into."
        else ->
            "The platform refused to give Prism a Robot, which is what synthesises keystrokes. On " +
                "macOS that means Accessibility permission; on Linux it usually means an X server " +
                "that is not accepting XTest."
    }

    /**
     * Types [text] into whatever has focus.
     *
     * Split: the directly typeable run goes as key events, and anything else goes through the
     * clipboard. Done per character rather than per string so a mixed string -- "ok 👍" -- is typed
     * normally up to the emoji and pasted for it, instead of the whole thing going through the
     * clipboard and losing the keystroke semantics a text field may rely on.
     */
    fun type(text: String) {
        val machine = robot ?: return
        val buffer = StringBuilder()

        fun flushAsPaste() {
            if (buffer.isEmpty()) return
            paste(buffer.toString())
            buffer.setLength(0)
        }

        text.forEach { ch ->
            val code = typeableCode(ch)
            if (code == null) {
                buffer.append(ch)
                return@forEach
            }
            flushAsPaste()
            runCatching { press(machine, code.first, code.second) }
        }
        flushAsPaste()
    }

    /** One backspace. */
    fun backspace() {
        val machine = robot ?: return
        runCatching { press(machine, KeyEvent.VK_BACK_SPACE, shift = false) }
    }

    /** Enter, for the keyboard's return key. */
    fun enter() {
        val machine = robot ?: return
        runCatching { press(machine, KeyEvent.VK_ENTER, shift = false) }
    }

    /**
     * Deletes [count] characters and types [replacement].
     *
     * What autocorrect and tapping a suggestion both need: the word being composed has already been
     * typed into the target, so replacing it means removing exactly what was sent.
     */
    fun replace(count: Int, replacement: String) {
        val machine = robot ?: return
        repeat(count.coerceAtLeast(0)) {
            runCatching { press(machine, KeyEvent.VK_BACK_SPACE, shift = false) }
        }
        type(replacement)
    }

    /**
     * A virtual key code and whether shift is needed, or null when the character has none.
     *
     * CONSERVATIVE ON PURPOSE. Only ASCII, and only the mappings that are the same on every
     * QWERTY-family layout. `getExtendedKeyCodeForChar` would offer more and would be wrong on a
     * layout the user actually has -- a German keyboard's `y` and `z` are swapped relative to the
     * key codes, so trusting it there types the other letter.
     */
    private fun typeableCode(ch: Char): Pair<Int, Boolean>? = when {
        ch in 'a'..'z' -> (KeyEvent.VK_A + (ch - 'a')) to false
        ch in 'A'..'Z' -> (KeyEvent.VK_A + (ch - 'A')) to true
        ch in '0'..'9' -> (KeyEvent.VK_0 + (ch - '0')) to false
        ch == ' ' -> KeyEvent.VK_SPACE to false
        ch == '\n' -> KeyEvent.VK_ENTER to false
        ch == '\t' -> KeyEvent.VK_TAB to false
        // The unshifted punctuation keys, which sit in the same place on every layout this is
        // likely to meet. Shifted punctuation is deliberately NOT here: the shifted face of a key
        // differs between layouts (a UK keyboard's shift-2 is " and a US one's is @), and getting
        // it wrong types a different character silently. Those go through the clipboard.
        ch == '-' -> KeyEvent.VK_MINUS to false
        ch == '=' -> KeyEvent.VK_EQUALS to false
        ch == '[' -> KeyEvent.VK_OPEN_BRACKET to false
        ch == ']' -> KeyEvent.VK_CLOSE_BRACKET to false
        ch == '\\' -> KeyEvent.VK_BACK_SLASH to false
        ch == ';' -> KeyEvent.VK_SEMICOLON to false
        ch == '\'' -> KeyEvent.VK_QUOTE to false
        ch == ',' -> KeyEvent.VK_COMMA to false
        ch == '.' -> KeyEvent.VK_PERIOD to false
        ch == '/' -> KeyEvent.VK_SLASH to false
        ch == '`' -> KeyEvent.VK_BACK_QUOTE to false
        else -> null
    }

    private fun press(machine: Robot, code: Int, shift: Boolean) {
        if (shift) machine.keyPress(KeyEvent.VK_SHIFT)
        machine.keyPress(code)
        machine.keyRelease(code)
        if (shift) machine.keyRelease(KeyEvent.VK_SHIFT)
    }

    /**
     * Pastes [text], putting the clipboard back afterwards.
     *
     * THE RESTORE IS THE POINT. A keyboard that silently replaced whatever the user had copied, every
     * time they typed an emoji, would be a worse bug than not supporting emoji -- and the restore is
     * delayed, because the target application reads the clipboard asynchronously after Ctrl+V and
     * putting the old contents back immediately races it.
     */
    private fun paste(text: String) {
        val machine = robot ?: return
        val clipboard = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
        }.getOrNull() ?: return

        val previous = runCatching {
            clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()

        runCatching { clipboard.setContents(StringSelection(text), null) }

        val modifier = if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
            KeyEvent.VK_META
        } else {
            KeyEvent.VK_CONTROL
        }
        runCatching {
            machine.keyPress(modifier)
            machine.keyPress(KeyEvent.VK_V)
            machine.keyRelease(KeyEvent.VK_V)
            machine.keyRelease(modifier)
        }

        if (previous != null) {
            // 300 ms, on a daemon thread: long enough for the paste to have been read, short enough
            // that the user is unlikely to copy something else in between.
            Thread({
                runCatching { Thread.sleep(300) }
                runCatching { clipboard.setContents(StringSelection(previous), null) }
            }, "writer-clipboard-restore").apply { isDaemon = true }.start()
        }
    }
}
