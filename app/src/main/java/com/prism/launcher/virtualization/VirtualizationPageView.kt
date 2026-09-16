package com.prism.launcher.virtualization

import android.content.ComponentName
import android.content.Context
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.R

/**
 * Launcher page that hosts a virtualized OS (PrismOS or a user-supplied ISO).
 *
 * Lifecycle:
 *  - [onAttachedToWindow] — reads settings and boots the VM when the surface is ready.
 *  - [onDetachedFromWindow] — pauses the VM so state is preserved across page swipes.
 *  - [launchApp] — routes a ComponentName into the running VM; queues it if still booting.
 */
class VirtualizationPageView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "VirtualizationPageView"
    }

    // Shared, not per-view: the page is recycled by the desktop pager while QEMU and the VNC
    // renderer keep running. See VmController.get.
    private val vmController = VmController.get(context)

    // Views — lateinit so we can reference them after inflate
    private lateinit var vmSurface: android.view.SurfaceView
    private lateinit var bootOverlay: FrameLayout
    private lateinit var tvBootLabel: TextView
    private lateinit var bootProgress: View
    private lateinit var controlBar: LinearLayout
    private lateinit var tvVmStatus: TextView
    private lateinit var btnVmPower: ImageButton
    private lateinit var btnVmKeyboard: ImageButton

    init {
        PrismLogger.logInfo(TAG, "VirtualizationPageView constructed")
        // Without these the view never receives key events, no matter what the IME sends.
        isFocusable = true
        isFocusableInTouchMode = true
        LayoutInflater.from(context).inflate(R.layout.page_virtualization_root, this, true)

        vmSurface    = findViewById(R.id.vmSurface)
        bootOverlay  = findViewById(R.id.bootOverlay)
        tvBootLabel  = findViewById(R.id.tvBootLabel)
        bootProgress = findViewById(R.id.bootProgress)
        controlBar   = findViewById(R.id.controlBar)
        tvVmStatus   = findViewById(R.id.tvVmStatus)
        btnVmPower   = findViewById(R.id.btnVmPower)
        btnVmKeyboard = findViewById(R.id.btnVmKeyboard)

        // Re-registered on attach, cleared on detach. The controller is process-wide now, so a
        // callback left behind by a recycled page would keep that page (and its whole view tree)
        // alive for the life of the app.
        vmController.onStateChanged = { state -> post { applyState(state) } }

        btnVmPower.setOnClickListener {
            when (vmController.state) {
                VmController.State.RUNNING, VmController.State.PAUSED -> vmController.stop()
                VmController.State.STOPPED, VmController.State.ERROR  -> bootFromSettings()
                else -> {}
            }
        }

        btnVmKeyboard.setOnClickListener { toggleGuestKeyboard() }

        // The surface itself takes focus, so tapping the VM and typing works without hunting for
        // the keyboard button first.
        vmSurface.setOnClickListener {
            if (vmController.state == VmController.State.RUNNING) showGuestKeyboard()
        }

        vmSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                PrismLogger.logInfo(TAG, "surfaceCreated()")
                // REATTACH BEFORE BOOTING. A surface arrives every time this page is rebuilt, and
                // the VM behind it may already be running -- QEMU is a subprocess, so it survives
                // both page recycling and the app process being restarted. Booting unconditionally
                // meant a second QEMU that could not bind the VNC port and died on the spot.
                if (vmController.ensureVncConnected(holder.surface)) {
                    PrismLogger.logInfo(TAG, "surfaceCreated(): reattached to a VM that was already running")
                    applyState(vmController.state)
                    return
                }
                bootFromSettings()
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                PrismLogger.logInfo(TAG, "surfaceDestroyed()")
                vmController.pause()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PrismLogger.logInfo(TAG, "onAttachedToWindow() — vmState=${vmController.state}")
        // Reclaim the callback: a previous instance of this page may have cleared it on its way
        // out, and the shared controller has no idea which page is on screen.
        vmController.onStateChanged = { state -> post { applyState(state) } }
        applyState(vmController.state)
        // SurfaceHolder callback handles boot; if surface already exists resume
        val surface = vmSurface.holder.surface
        if (surface != null && surface.isValid && vmController.state == VmController.State.PAUSED) {
            vmController.resume(surface)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Dropped before pausing, so a state change triggered by pause() cannot call back into a
        // view that is on its way out.
        vmController.onStateChanged = null
        vmController.pause()
    }

    // -- Windows mode ---------------------------------------------------------

    /**
     * The Windows session, created only in Windows mode.
     *
     * Held here rather than in [VmController] because the two are alternatives, not layers: one
     * boots a guest OS under QEMU, the other runs Wine under PRoot, and only one owns the surface at
     * a time. Merging them into one controller would mean a state machine with two unrelated halves.
     */
    private var wineSession: WineSession? = null

    /** True when the page should be a Windows runtime rather than the guest-OS viewer. */
    private fun windowsMode(): Boolean = PrismSettings.getWindowsMode()

    /**
     * Runs [exe], installing the compatibility layer first if it is missing.
     *
     * Called from the launcher when a .exe arrives through [ExeLaunchActivity], and from this page's
     * own controls.
     */
    fun runWindowsExecutable(exe: java.io.File?) {
        if (!windowsMode()) {
            android.widget.Toast.makeText(
                context,
                "Turn on “Switch to running Windows executables” in Settings first",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        WineContainer.unavailableReason(context)?.let { reason ->
            if (!WineInstaller.isInstalled(context)) {
                promptWindowsInstall(exe)
            } else {
                android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        startWineSession(exe)
    }

    private fun startWineSession(exe: java.io.File?) {
        // The QEMU guest and a Wine session cannot share the surface, so whichever was running
        // stops before the other starts.
        vmController.pause()

        val container = WineContainer.containers(context).firstOrNull()
            ?: WineContainer.createContainer(context, "default")

        val session = wineSession ?: WineSession(context).also { wineSession = it }
        session.onStateChanged = { state, detail ->
            post {
                when (state) {
                    WineSession.State.RUNNING -> {
                        PrismLogger.logSuccess(TAG, "Windows session running: ${detail.orEmpty()}")
                        val surface = vmSurface.holder.surface
                        if (surface != null && surface.isValid) {
                            vmController.attachVnc(session.vncPort, surface)
                        }
                    }
                    WineSession.State.FAILED ->
                        android.widget.Toast.makeText(
                            context, detail ?: "The Windows session failed",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    else -> Unit
                }
            }
        }
        session.start(container, exe)
    }

    /** Offers to fetch the layer, and explains what it is before downloading a gigabyte. */
    private fun promptWindowsInstall(pendingExe: java.io.File?) {
        val url = PrismSettings.getWindowsLayerUrl()
        if (url.isBlank()) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Windows layer not configured")
                .setMessage(
                    "Running .exe files needs Wine, box64 and a small Linux root filesystem. " +
                        "Prism does not bundle them -- they are large and separately licensed -- so " +
                        "set a source archive in Settings > OS Virtualization.\n\nThe archive must " +
                        "contain:\n\n" + WineInstaller.expectedLayout()
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Install the Windows layer?")
            .setMessage(
                "Downloads and unpacks Wine, box64 and a Linux root filesystem. This is a large " +
                    "download and happens once."
            )
            .setPositiveButton("Install") { _, _ -> installWindowsLayer(pendingExe) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installWindowsLayer(pendingExe: java.io.File?) {
        WineInstaller.sourceUrl = PrismSettings.getWindowsLayerUrl()
        val toast = android.widget.Toast.makeText(context, "Installing…", android.widget.Toast.LENGTH_SHORT)
        toast.show()

        Thread({
            val error = WineInstaller.install(context) { percent, message ->
                PrismLogger.logDebug(TAG, "wine install $percent% $message")
            }
            post {
                if (error == null) {
                    android.widget.Toast.makeText(
                        context, "Windows layer installed", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    startWineSession(pendingExe)
                } else {
                    android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "wine-install").apply { isDaemon = true; start() }
    }

    /**
     * Forwards [cn] to the running PrismOS VM.
     * If the VM is still booting the request is queued inside [VmController] and
     * replayed automatically once RUNNING.
     */
    fun launchApp(cn: ComponentName) {
        vmController.sendAppIntent(cn)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun bootFromSettings() {
        val surface = vmSurface.holder.surface
        if (surface == null || !surface.isValid) {
            PrismLogger.logWarning(TAG, "bootFromSettings(): skipped — surface not ready (surface=$surface)")
            return
        }
        if (vmController.state == VmController.State.RUNNING ||
            vmController.state == VmController.State.BOOTING) {
            PrismLogger.logInfo(TAG, "bootFromSettings(): skipped — VM already ${vmController.state}")
            return
        }

        val mode = when (PrismSettings.getVirtualizationMode()) {
            PrismSettings.VIRT_MODE_CUSTOM_ISO -> VmController.Mode.CUSTOM_ISO
            else -> VmController.Mode.PRISM_OS
        }
        val isoPath = PrismSettings.getCustomIsoPath().takeIf { it.isNotBlank() }
        PrismLogger.logInfo(TAG, "bootFromSettings(): mode=$mode isoPath=$isoPath")

        vmController.start(mode, isoPath, surface)
    }

    /**
     * Keyboard input for the guest.
     *
     * ## Why this view accepts keys itself instead of using an EditText
     *
     * A terminal is not a text field. There is no buffer to edit, no cursor to move, and every
     * keystroke has to reach the guest the moment it happens -- including the ones an EditText
     * would swallow, like Ctrl-C, Tab completion and the arrow keys. So the view takes focus
     * directly, receives raw key events, and forwards each one down the VNC connection.
     *
     * The IME still needs somewhere to send composed text, which is what [onCreateInputConnection]
     * provides: a null-input connection that reports every keystroke rather than accumulating a
     * buffer nobody reads.
     */
    /**
     * The keys a phone keyboard does not have.
     *
     * WHY THIS EXISTS AT ALL. A shell is driven by Esc, Tab, Ctrl and the arrows, and no soft
     * keyboard offers any of them. Enter is here too for a subtler reason: IMEs disagree about
     * whether their return key sends a key event, calls performEditorAction, or simply commits a
     * newline as text -- three routes with three different failure modes. This button always sends
     * the Return keysym, so it is the one that cannot be misinterpreted.
     *
     * CTRL IS STICKY, because a modifier held on a touchscreen needs two fingers otherwise. Tapping
     * it arms the next keystroke and then disarms itself, which is how Ctrl-C is reachable at all.
     */
    private var ctrlArmed = false

    private fun buildTerminalKeys() {
        val row = findViewById<android.widget.LinearLayout>(R.id.termKeys)
        if (row.childCount > 0) return

        val keys = listOf(
            "esc" to VncKeysyms.ESCAPE,
            "tab" to VncKeysyms.TAB,
            "ctrl" to -1,                    // handled specially: sticky modifier
            "\u2190" to VncKeysyms.LEFT,
            "\u2193" to VncKeysyms.DOWN,
            "\u2191" to VncKeysyms.UP,
            "\u2192" to VncKeysyms.RIGHT,
            "\u21b5" to VncKeysyms.RETURN,
        )

        for ((label, keysym) in keys) {
            val button = android.widget.TextView(context).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(34, 18, 34, 18)
                setBackgroundResource(R.drawable.hotseat_glass_bg)
                isClickable = true
                setOnClickListener {
                    if (keysym == -1) {
                        ctrlArmed = !ctrlArmed
                        alpha = if (ctrlArmed) 1f else 0.6f
                        return@setOnClickListener
                    }
                    reattachIfNeeded()
                    if (ctrlArmed) {
                        vmController.sendKeyRaw(VncKeysyms.CONTROL_L, true)
                        vmController.sendKey(keysym)
                        vmController.sendKeyRaw(VncKeysyms.CONTROL_L, false)
                        ctrlArmed = false
                        row.getChildAt(2)?.alpha = 0.6f
                    } else {
                        vmController.sendKey(keysym)
                    }
                }
                if (label == "ctrl") alpha = 0.6f
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = 10 }
            row.addView(button, lp)
        }
    }

    private fun toggleGuestKeyboard() {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (isKeyboardShowing) {
            imm?.hideSoftInputFromWindow(windowToken, 0)
            isKeyboardShowing = false
        } else {
            showGuestKeyboard()
        }
    }

    private fun updateTerminalKeyRow(running: Boolean) {
        buildTerminalKeys()
        findViewById<android.view.View>(R.id.termKeyRow).visibility =
            if (running) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showGuestKeyboard() {
        val focused = requestFocus()
        PrismLogger.logInfo(TAG, "showGuestKeyboard(): focus=$focused state=${vmController.state}")
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        isKeyboardShowing = true
    }

    private var isKeyboardShowing = false

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Reports keystrokes rather than buffering them.
     *
     * TYPE_NULL is what tells the IME to send raw key events instead of composing text. Without it
     * a soft keyboard batches input and commits it as a string, which loses the timing a terminal
     * depends on -- and never delivers Ctrl or the arrow keys at all.
     */
    override fun onCreateInputConnection(
        outAttrs: android.view.inputmethod.EditorInfo,
    ): android.view.inputmethod.InputConnection {
        // VISIBLE_PASSWORD, not TYPE_NULL.
        //
        // TYPE_NULL is supposed to make an IME emit raw key events, and most soft keyboards --
        // Gboard included -- ignore it and commit text through the InputConnection anyway. So
        // onKeyDown never fires and nothing reaches the guest, which is exactly what happened.
        // The visible-password variation is what terminal apps use: it disables autocorrect and
        // composition, so every character is committed on its own the instant it is typed.
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or
            android.view.inputmethod.EditorInfo.IME_ACTION_NONE
        return GuestInputConnection()
    }

    /**
     * Turns whatever the IME does into VNC key events.
     *
     * BOTH ROUTES ARE HANDLED, because keyboards disagree about which they use. A hardware keyboard
     * and some soft keyboards deliver key events; Gboard and most others commit text. Implementing
     * only one path works on the author's device and nowhere else.
     */
    private inner class GuestInputConnection : android.view.inputmethod.BaseInputConnection(
        this@VirtualizationPageView, false
    ) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            PrismLogger.logInfo(TAG, "guest input: commitText(\"$text\")")
            for (c in text) sendCharToGuest(c)
            return true
        }

        /**
         * A terminal has no composition buffer, so composing text is committed immediately.
         * Holding it back would mean the guest sees nothing until the IME decided a word was
         * finished -- and a shell prompt is not a word.
         */
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            commitText(text, newCursorPosition)

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { vmController.sendKey(VncKeysyms.BACKSPACE) }
            repeat(afterLength) { vmController.sendKey(VncKeysyms.DELETE) }
            return true
        }

        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                return onKeyDown(event.keyCode, event)
            }
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            vmController.sendKey(VncKeysyms.RETURN)
            return true
        }
    }

    /**
     * Restores a lost VNC connection, if the VM is still there.
     *
     * Silent when nothing is wrong. Exists because the connection can outlive neither the app
     * process nor a recycled page, while the VM itself outlives both.
     */
    private fun reattachIfNeeded() {
        if (vmController.hasVncConnection()) return
        val surface = vmSurface.holder.surface ?: return
        if (!surface.isValid) return
        if (vmController.ensureVncConnected(surface)) {
            PrismLogger.logInfo(TAG, "reattached to the running VM before sending input")
        }
    }

    /** One character, straight to the guest. */
    private fun sendCharToGuest(c: Char): Boolean {
        val keysym = VncKeysyms.forCharacter(c) ?: return false
        // A keystroke is the moment a lost connection actually matters, so try to get it back
        // rather than discarding what the user typed.
        reattachIfNeeded()
        val sent = vmController.sendKey(keysym)
        if (!sent) {
            PrismLogger.logWarning(
                TAG,
                "guest input: '''$c''' not delivered — no VNC connection is attached"
            )
        }
        return sent
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Deliberately NOT gated on the VM state. The connection is the thing that matters, and
        // sendKey already reports when there is none -- gating on RUNNING meant a state that had
        // not caught up yet silently ate everything the user typed.
        // Back must keep working as Back, or there is no way out of a focused VM.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)

        // Non-printable keys first: they have no character to derive a keysym from.
        VncKeysyms.forKeyCode(keyCode)?.let { keysym ->
            return sendToGuest(keysym, event)
        }

        // Everything else goes by the character the keyboard actually produced, which already
        // accounts for shift, layout and long-press accents.
        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode != 0) {
            VncKeysyms.forCharacter(unicode.toChar())?.let { keysym ->
                return sendToGuest(keysym, event, shiftAlreadyApplied = true)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Sends a keysym, wrapping it in Ctrl/Alt when those are held.
     *
     * Ctrl matters more than it looks: Ctrl-C, Ctrl-D and Ctrl-Z are how anyone actually drives a
     * shell, and none of them arrive as a character.
     */
    private fun sendToGuest(
        keysym: Int,
        event: android.view.KeyEvent,
        shiftAlreadyApplied: Boolean = false,
    ): Boolean {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed

        // Modifiers are HELD across the key, not tapped before it. sendKey would press and release
        // in one call, which turns Ctrl-C into a plain c.
        if (ctrl) vmController.sendKeyRaw(VncKeysyms.CONTROL_L, true)
        if (alt) vmController.sendKeyRaw(VncKeysyms.ALT_L, true)

        // The character already encodes shift, so re-applying it would send the wrong symbol.
        val sent = vmController.sendKey(keysym, shift = !shiftAlreadyApplied && event.isShiftPressed)

        if (alt) vmController.sendKeyRaw(VncKeysyms.ALT_L, false)
        if (ctrl) vmController.sendKeyRaw(VncKeysyms.CONTROL_L, false)

        if (!sent) {
            PrismLogger.logWarning(
                TAG,
                "guest input: keysym 0x${keysym.toString(16)} not delivered — " +
                    "VNC attached=${vmController.hasVncConnection()}"
            )
        }
        return sent
    }

    private fun applyState(state: VmController.State) {
        // The terminal keys follow the control bar: both are only meaningful while a VM is running.
        updateTerminalKeyRow(state == VmController.State.RUNNING)

        // vmSurface itself is never toggled here — it has to stay visible for its whole lifetime
        // (see the comment in page_virtualization_root.xml on why). bootOverlay's opaque
        // background covers it visually whenever the VM isn't actually rendering to it.
        when (state) {
            VmController.State.STOPPED -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.GONE
                tvBootLabel.text = context.getString(R.string.virt_status_stopped)
                controlBar.visibility = View.GONE
            }
            VmController.State.BOOTING -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.VISIBLE
                tvBootLabel.text = context.getString(R.string.virt_status_booting)
                controlBar.visibility = View.GONE
            }
            VmController.State.RUNNING -> {
                bootOverlay.visibility = View.GONE
                controlBar.visibility = View.VISIBLE
                tvVmStatus.text = context.getString(R.string.virt_status_running)
            }
            VmController.State.PAUSED -> {
                controlBar.visibility = View.VISIBLE
                tvVmStatus.text = context.getString(R.string.virt_status_paused)
            }
            VmController.State.ERROR -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.GONE
                val detail = vmController.lastError
                tvBootLabel.text = if (detail.isNullOrBlank()) {
                    context.getString(R.string.virt_status_error)
                } else {
                    "${context.getString(R.string.virt_status_error)}: $detail"
                }
                controlBar.visibility = View.GONE
            }
        }
    }
}
