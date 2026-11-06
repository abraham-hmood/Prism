package com.prism.launcher.virtualization

import com.nine.dbt.GuestLog
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

        /**
         * The app this page was last asked to show, remembered across page recycling.
         *
         * Process-wide rather than per-view for the same reason VmController is: the pager throws
         * this view away whenever it scrolls far enough, and the app it was showing is still running
         * in the other process.
         */
        @Volatile
        private var lastHostedPackage: String? = null

        /** Forgotten when the app is closed, so the page goes back to being a guest-OS viewer. */
        fun forgetHostedApp() {
            lastHostedPackage = null
        }
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
    private lateinit var appSurface: android.view.SurfaceView

    /**
     * The embedded Android app, when app virtualization is showing one here.
     *
     * Bound against the APPLICATION context, not this view's: the page is recycled every time the
     * user swipes away from it, and a binding held by a dead view would tear the app down on every
     * swipe. See [hostVirtualApp] for how a recycled page picks the running app back up.
     */
    private val embedder by lazy {
        com.prism.launcher.virtualapp.VirtualAppEmbedder(context.applicationContext, appSurface)
    }

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
        appSurface    = findViewById(R.id.appSurface)

        // ABOVE the launcher's own window, decided once and never changed.
        //
        // A SurfaceView's surface sits BEHIND its host window by default -- the window punches a
        // hole to show it through -- and an embedded hierarchy inherits that order. The window
        // manager then lists the launcher's window above the embedded one, with a touchable region
        // covering the whole screen, so every tap is consumed by the launcher and the app renders
        // perfectly while responding to nothing. `dumpsys input` showed exactly that: the host at
        // index 27 and `Embedded{...}` at 28.
        //
        // Set here rather than at attach time because changing it recreates the surface, which
        // orphans an already-attached child package. Safe because nothing of Prism's is drawn over
        // this area while an app is showing: the boot overlay and the control bar are both gone.
        appSurface.setZOrderOnTop(true)

        // Touches on the app's surface belong to the app. Consumed here and forwarded, because the
        // window manager does not route them into the embedded hierarchy on this platform -- see
        // VirtualAppHostService.dispatchTouch for what was tried and what it did.
        appSurface.setOnTouchListener { _, event ->
            if (embedder.current == null) false else embedder.dispatchTouch(event)
        }

        embedder.onShown = { label ->
            bootOverlay.visibility = View.GONE
            // The bar stays, and is the only way off this page while an app is up: the app consumes
            // touches on its own surface, so the pager cannot be swiped through it.
            controlBar.visibility = View.VISIBLE
            tvVmStatus.text = label
            btnVmPower.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            btnVmPower.contentDescription = context.getString(R.string.virt_close_app)
            btnVmKeyboard.setImageResource(android.R.drawable.ic_menu_revert)
            btnVmKeyboard.contentDescription = context.getString(R.string.virt_back_in_app)
            PrismLogger.logInfo(TAG, "Showing $label on the virtualization page")
        }

        embedder.onExhausted = { closeVirtualApp() }
        embedder.onFailure = { reason ->
            appSurface.visibility = View.GONE
            bootOverlay.visibility = View.VISIBLE
            bootProgress.visibility = View.GONE
            tvBootLabel.text = reason
            PrismLogger.logWarning(TAG, "Could not embed the app: $reason")
        }

        // Re-registered on attach, cleared on detach. The controller is process-wide now, so a
        // callback left behind by a recycled page would keep that page (and its whole view tree)
        // alive for the life of the app.
        vmController.onStateChanged = { state -> post { applyState(state) } }

        btnVmPower.setOnClickListener {
            if (showingApp()) {
                closeVirtualApp()
                return@setOnClickListener
            }
            when (vmController.state) {
                VmController.State.RUNNING, VmController.State.PAUSED -> vmController.stop()
                VmController.State.STOPPED, VmController.State.ERROR  -> bootFromSettings()
                else -> {}
            }
        }

        btnVmKeyboard.setOnClickListener {
            if (showingApp()) embedder.goBack() else toggleGuestKeyboard()
        }

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
        // A page that was recycled while an app was showing picks it back up. The service returns
        // the surface it is already rendering into rather than reloading, so the app does not
        // restart just because the user swiped away and back.
        lastHostedPackage?.let { hostVirtualApp(it) }

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

    // -- Windows and Linux programs, through NINE ------------------------------

    /**
     * True when the page runs Windows and Linux programs rather than booting a guest OS.
     *
     * The setting kept its old key (it was "Windows mode"); it now means NINE, which runs both
     * kinds with its own Windows and Linux layers -- nothing is installed to make it work.
     */
    private fun windowsMode(): Boolean = PrismSettings.getWindowsMode()

    /** The console a running program writes into, built the first time one runs. */
    private var programConsole: android.widget.LinearLayout? = null
    private var programOutput: TextView? = null
    private var programTitle: TextView? = null
    private var programStop: android.widget.Button? = null
    @Volatile private var programRunning = false

    // -- Embedded Android apps -------------------------------------------------

    /**
     * Shows a virtualized Android app on this page.
     *
     * The app itself runs in `:virtualapp`; this page shows the surface it renders into. Nothing
     * about the app's code, its data or the vault key enters the launcher's process -- see
     * VirtualAppHostService for why that separation is the whole reason this is not simply hosted
     * here directly.
     */
    fun hostVirtualApp(packageName: String) {
        lastHostedPackage = packageName
        bootProgress.visibility = View.VISIBLE
        bootOverlay.visibility = View.VISIBLE
        tvBootLabel.text = context.getString(R.string.virt_status_opening_app)
        // Stops QEMU from owning the surface underneath, for the same reason the two halves of this
        // page have always been exclusive: only one of them can be on screen.
        vmController.pause()
        // And hides its surface outright. Two SurfaceViews in one hierarchy have to be ordered
        // explicitly, and the call that does it (setZOrderMediaOverlay) lifts the surface above the
        // host window, which breaks touch routing into the embedded app. With the guest's surface
        // gone there is nothing to order against, so the app can stay where input works.
        vmSurface.visibility = View.GONE
        embedder.show(packageName)
    }

    /** True while this page is showing an embedded app rather than a guest OS. */
    private fun showingApp(): Boolean = embedder.current != null

    /**
     * Closes the embedded app and hands the page back to the guest-OS viewer.
     *
     * Stopping the embedder is also what seals the app's data: the binding drops, and VaultService
     * encrypts the working directory again.
     */
    fun closeVirtualApp() {
        embedder.stop()
        forgetHostedApp()
        appSurface.visibility = View.GONE
        vmSurface.visibility = View.VISIBLE
        btnVmPower.setImageResource(android.R.drawable.ic_lock_power_off)
        btnVmKeyboard.setImageResource(android.R.drawable.ic_menu_myplaces)
        applyState(vmController.state)
    }

    /**
     * Runs a Windows (.exe) or Linux (ELF) x86-64 program through NINE.
     *
     * Called from the launcher when a program arrives through [ExeLaunchActivity], and from this
     * page. The program's own output and NINE's log stream into a console on the page; Stop asks
     * NINE to end it.
     */
    fun runProgram(program: java.io.File) {
        if (!windowsMode()) {
            android.widget.Toast.makeText(
                context,
                "Turn on “Run Windows and Linux programs” in Settings first",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (programRunning) {
            android.widget.Toast.makeText(context, "A program is already running; stop it first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val kind = when (val found = com.prism.launcher.nine.NineProgram.identify(program)) {
            is com.prism.launcher.nine.NineProgram.Identified.Refused -> {
                showProgramConsole(program.name)
                appendProgramLine(found.reason)
                return
            }
            is com.prism.launcher.nine.NineProgram.Identified.Runnable -> found.kind
        }
        if (!NineRuntime.available()) {
            showProgramConsole(program.name)
            appendProgramLine(NineRuntime.describe())
            return
        }

        // The guest OS and a program cannot both own the page.
        vmController.pause()
        showProgramConsole(program.name)
        appendProgramLine("Running ${program.name} (${kind.title}) with NINE…")
        programRunning = true
        programStop?.isEnabled = true
        GuestLog.setListener { line -> post { appendProgramLine(line) } }

        Thread({
            val result = NineRuntime.run(program, java.io.File(context.cacheDir, "nine"))
            post {
                programRunning = false
                programStop?.isEnabled = false
                GuestLog.setListener(null)
                appendProgramLine(
                    result.fold(
                        { status -> "Finished. NINE reported: " + status.take(600) },
                        { error -> "NINE could not run it: " + (error.message ?: error.javaClass.simpleName) },
                    ),
                )
                PrismLogger.logInfo(TAG, "${program.name} finished: ${result.getOrNull()?.take(200) ?: result.exceptionOrNull()?.message}")
            }
        }, "nine-run").apply { isDaemon = true; start() }
    }

    /** Kept for the launcher's older entry point; a program is a program. */
    fun runWindowsExecutable(exe: java.io.File?) {
        if (exe != null) runProgram(exe)
    }

    private fun showProgramConsole(title: String) {
        bootOverlay.visibility = View.GONE
        val console = programConsole ?: buildProgramConsole().also { programConsole = it }
        console.visibility = View.VISIBLE
        programTitle?.text = title
        programOutput?.text = ""
    }

    private fun buildProgramConsole(): android.widget.LinearLayout {
        val pad = com.prism.launcher.nora.IosUi.dp(context, 12f)
        val console = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xF00A0A12.toInt())
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }
        val header = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        programTitle = TextView(context).apply {
            setTextColor(0xFF00E5FF.toInt())
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(programTitle, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        programStop = android.widget.Button(context).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener {
                NineRuntime.stop()
                appendProgramLine("Stopping…")
            }
        }
        header.addView(programStop)
        header.addView(android.widget.Button(context).apply {
            text = "Close"
            setOnClickListener {
                if (programRunning) NineRuntime.stop()
                console.visibility = View.GONE
            }
        })
        console.addView(header)
        programOutput = TextView(context).apply {
            setTextColor(0xFFE6E6EE.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroller = android.widget.ScrollView(context).apply { addView(programOutput) }
        console.addView(scroller, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(console, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return console
    }

    /** Appends a line, keeping the console to its last few thousand lines. */
    private fun appendProgramLine(line: String) {
        val view = programOutput ?: return
        view.append(line.removeSuffix("\n") + "\n")
        val text = view.text
        if (text.length > 200_000) view.text = text.subSequence(text.length - 150_000, text.length)
        (view.parent as? android.widget.ScrollView)?.post { (view.parent as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN) }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // An embedded app is laid out against a size this side chose, so it has to be told when
        // that changes -- otherwise a rotation leaves it stretched rather than relaid out.
        if (showingApp()) embedder.resize(appSurface.width, appSurface.height)
    }

    private fun bootFromSettings() {
        // PROGRAM MODE BOOTS NOTHING. The two halves of this page are exclusive -- one runs a guest
        // OS under QEMU, the other runs Windows and Linux programs through NINE, and only one owns the surface
        // (the class comment says so). This check was missing, so turning "switch to running Windows
        // executables" on changed which UI appeared while the surface callback went on booting
        // PrismOS underneath it: the setting looked ignored because, for the part that matters, it
        // was.
        if (windowsMode()) {
            PrismLogger.logInfo(TAG, "bootFromSettings(): skipped — Windows mode is on")
            return
        }

        // An app is on screen here; booting a guest OS underneath it would fight for the surface.
        if (showingApp()) {
            PrismLogger.logInfo(TAG, "bootFromSettings(): skipped — an app is embedded")
            return
        }

        // APP VIRTUALIZATION BOOTS NOTHING EITHER, for the same reason and with the same shape as
        // the check above. With "Virtualize Android apps" on, an app's code runs in Prism's own
        // process (VirtualizedAppLauncher) and there is no guest for a guest OS to be: QEMU would
        // burn a core and several hundred megabytes booting PrismOS behind a page nothing routes
        // to. The setting looked ignored because the half that costs anything went on running.
        if (PrismSettings.getVirtualizeAndroidApps()) {
            PrismLogger.logInfo(TAG, "bootFromSettings(): skipped — app virtualization is on")
            tvBootLabel.text = context.getString(R.string.virt_status_app_virtualization)
            bootOverlay.visibility = View.VISIBLE
            bootProgress.visibility = View.GONE
            controlBar.visibility = View.GONE
            return
        }

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
        // An embedded app owns the bar and the overlay while it is up; the guest's state must not
        // reach in and relabel them.
        if (showingApp()) return

        // The terminal keys follow the control bar: both are only meaningful while a VM is running.
        updateTerminalKeyRow(state == VmController.State.RUNNING)

        // vmSurface itself is never toggled here — it has to stay visible for its whole lifetime
        // (see the comment in page_virtualization_root.xml on why). bootOverlay's opaque
        // background covers it visually whenever the VM isn't actually rendering to it.
        when (state) {
            VmController.State.STOPPED -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.GONE
                // "Stopped" would read as something that failed to start. With app virtualization
                // on nothing was meant to start, and the overlay should say which of the two it is.
                tvBootLabel.text = if (PrismSettings.getVirtualizeAndroidApps()) {
                    context.getString(R.string.virt_status_app_virtualization)
                } else {
                    context.getString(R.string.virt_status_stopped)
                }
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
