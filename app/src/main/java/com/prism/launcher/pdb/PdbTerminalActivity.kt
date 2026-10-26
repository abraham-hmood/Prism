package com.prism.launcher.pdb

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlin.concurrent.thread

/**
 * A shell on one bridged device.
 *
 * The terminal is a reader around [Pdb.Client]. It sends one command at a time so output cannot
 * interleave, and the prompt stays disabled until the target reports that command finished. That
 * is slower than firing everything at once, and it is what makes `cat` output legible.
 */
class PdbTerminalActivity : PrismBaseActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var run: TextView
    private lateinit var state: TextView

    private var client: Pdb.Client? = null
    private var runner: PdbAdb? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        connect()
    }

    override fun onDestroy() {
        runCatching { client?.bye() }
        client?.close()
        client = null
        runner = null
        super.onDestroy()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0F.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 12f),
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 12f),
            )
            setBackgroundColor(0xFF111119.toInt())
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(0xFFE8E8F0.toInt())
            setPadding(0, 0, IosUi.dp(this@PdbTerminalActivity, 12f), 0)
            setOnClickListener { finish() }
        })
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Prism device" }
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(0xFFE8E8F0.toInt())
        })
        state = TextView(this).apply {
            text = "Connecting…"
            textSize = 11f
            setTextColor(0xFF8A8A9A.toInt())
        }
        title.addView(state)
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "PDB"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0xFF6B6B7A.toInt())
        })
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFF0B0B0F.toInt())
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(0xFFD7D7E0.toInt())
            setTextIsSelectable(true)
            setPadding(
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 12f),
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 12f),
            )
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@PdbTerminalActivity, 10f),
                IosUi.dp(this@PdbTerminalActivity, 8f),
                IosUi.dp(this@PdbTerminalActivity, 10f),
                IosUi.dp(this@PdbTerminalActivity, 8f),
            )
            setBackgroundColor(0xFF111119.toInt())
        }
        input = EditText(this).apply {
            hint = "adb shell ls /sdcard"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE8E8F0.toInt())
            setHintTextColor(0xFF6B6B7A.toInt())
            background = IosUi.fieldBackground(this@PdbTerminalActivity).apply {
                setTint(0xFF242432.toInt())
            }
            setPadding(
                IosUi.dp(this@PdbTerminalActivity, 12f),
                IosUi.dp(this@PdbTerminalActivity, 9f),
                IosUi.dp(this@PdbTerminalActivity, 12f),
                IosUi.dp(this@PdbTerminalActivity, 9f),
            )
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            isEnabled = false
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else false
            }
        }
        entry.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        run = TextView(this).apply {
            text = "Run"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            background = IosUi.filledBackground(this@PdbTerminalActivity, 0xFF5B5BD6.toInt())
            setPadding(
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 9f),
                IosUi.dp(this@PdbTerminalActivity, 14f),
                IosUi.dp(this@PdbTerminalActivity, 9f),
            )
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { send() }
        }
        entry.addView(run, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = IosUi.dp(this@PdbTerminalActivity, 8f) })
        root.addView(entry, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        return root
    }

    private fun connect() {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val target = Pdb.Target(
            fingerprint = intent.getStringExtra(EXTRA_FP).orEmpty(),
            name = name,
            platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty(),
            ip = intent.getStringExtra(EXTRA_IP).orEmpty(),
        )
        if (target.ip.isBlank()) {
            writeError("No mesh address for this device.\n")
            setState("Offline", failed = true)
            return
        }
        thread(name = "prism-pdb-terminal", isDaemon = true) {
            val connection = Pdb.Client(target)
            try {
                connection.connect()
                var hello = connection.hello()
                if (hello is Pdb.Hello.Challenge) {
                    // THE TARGET IS SHOWING A CODE RIGHT NOW, and it holds that screen open for
                    // exactly as long as this socket lives. Ask for the words here and keep the
                    // connection: closing it would dismiss the screen before anybody could read
                    // it, which is why this used to flash and vanish.
                    hello = verifyPhrase(connection, target, hello.salt) ?: run {
                        connection.close()
                        return@thread
                    }
                }
                when (val answer = hello) {
                    is Pdb.Hello.Trusted -> startShell(connection, target)
                    is Pdb.Hello.Challenge -> {
                        connection.close()
                        runOnUiThread {
                            setState("Needs verification", failed = true)
                            writeError("That device did not accept a phrase. Try again.\n")
                        }
                    }
                    is Pdb.Hello.Denied -> {
                        connection.close()
                        runOnUiThread {
                            setState("Refused", failed = true)
                            writeError(answer.reason + "\n")
                        }
                    }
                }
            } catch (error: Exception) {
                connection.close()
                runOnUiThread {
                    setState("Disconnected", failed = true)
                    writeError(
                        "Could not open the bridge: " + (error.message ?: "connection failed") + "\n",
                    )
                }
            }
        }
    }

    /**
     * Asks for the code the target is showing and proves it on the same socket.
     *
     * Blocks this background thread while the dialog is up, which is what keeps the target's side
     * of the handshake alive; the UI is not blocked because only this thread waits. Returns a
     * [Pdb.Hello.Trusted] once the pair is proved and remembered, or null when the user cancelled
     * or the code was wrong -- in which case the caller closes the connection.
     */
    private fun verifyPhrase(
        connection: Pdb.Client,
        target: Pdb.Target,
        salt: ByteArray,
    ): Pdb.Hello? {
        runOnUiThread {
            setState("Needs verification")
            write(
                "This device is trusted, but a shell needs the code shown on " +
                    target.name.ifBlank { "the other device" } + "'s screen.\n\n",
            )
        }
        val typed = askPhrase(target.name)
        if (typed.isNullOrBlank()) {
            runOnUiThread {
                setState("Verification cancelled", failed = true)
                writeError("No code was entered, so nothing was opened.\n")
            }
            return null
        }
        val proved = connection.prove(typed, salt)
        if (proved.isFailure) {
            runOnUiThread {
                setState("Not verified", failed = true)
                writeError(
                    (proved.exceptionOrNull()?.message ?: "The code was refused.") + "\n" +
                        "Open the device again to see a new code on it.\n",
                )
            }
            return null
        }
        // Remembered before the shell opens: this secret authenticates every later session, and
        // losing it would mean another code.
        runCatching { Pdb.remember(connection.target, connection.pairedSecret) }
        return Pdb.Hello.Trusted
    }

    /** Shows the code field and waits for an answer. Called from the background thread. */
    private fun askPhrase(name: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val typed = java.util.concurrent.atomic.AtomicReference<String?>(null)
        runOnUiThread {
            val field = EditText(this).apply {
                hint = "12 characters"
                textSize = 18f
                typeface = Typeface.MONOSPACE
                setTextColor(IosUi.label(this@PdbTerminalActivity))
                setHintTextColor(IosUi.tertiaryLabel(this@PdbTerminalActivity))
                setSingleLine(true)
            }
            val dialog = com.prism.launcher.PrismDialogFactory.show(
                this,
                "Type the code shown on " + name.ifBlank { "the other device" },
                message = "",
                customView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(field, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ))
                    addView(TextView(this@PdbTerminalActivity).apply {
                        text = "The code stays on that screen and is never sent. Typing it here " +
                            "proves you can see it, which is what a shell on somebody's device requires."
                        textSize = 11f
                        setTextColor(IosUi.tertiaryLabel(this@PdbTerminalActivity))
                        setPadding(0, IosUi.dp(this@PdbTerminalActivity, 10f), 0, 0)
                    })
                },
                positiveText = "Verify",
                negativeText = "Cancel",
                onPositive = { typed.set(field.text?.toString()) },
                onNegative = { typed.set(null) },
            )
            // Fires after either button, and also when the dialog is dismissed any other way, so
            // the waiting thread is never left blocked on a screen nobody can answer.
            dialog.setOnDismissListener { latch.countDown() }
        }
        // Bounded to the target's own two-minute window; past it the phrase has expired anyway.
        if (!latch.await(150, java.util.concurrent.TimeUnit.SECONDS)) return null
        return typed.get()
    }

    /** Opens the shell on a connection whose pair is already proved. */
    private fun startShell(connection: Pdb.Client, target: Pdb.Target) {
        val opened = connection.open()
        if (opened.isFailure) {
            throw IllegalStateException(
                opened.exceptionOrNull()?.message ?: "The target refused the shell.",
            )
        }
        client = connection
        runner = PdbAdb(
            connection,
            Pdb.Device(
                fingerprint = target.fingerprint,
                name = target.name,
                platform = target.platform,
                lastIp = target.ip,
                verifiedAt = 0L,
            ),
        ) { text, error ->
            runOnUiThread {
                if (error) writeError(text) else write(text)
            }
        }
        runOnUiThread {
            setState("Connected over the mesh")
            write("Prism Debug Bridge · " + target.name + "\n")
            write("Commands run as the Prism app on that device. Type `help`.\n\n")
            enable(true)
        }
    }

    private fun send() {
        if (busy) return
        val raw = input.text?.toString()?.trim().orEmpty()
        input.setText("")
        if (raw.isEmpty()) return
        if (raw == "clear") {
            output.text = ""
            return
        }
        if (raw == "exit" || raw == "quit") {
            finish()
            return
        }
        val active = runner
        if (active == null) {
            writeError("Not connected.\n")
            return
        }
        write("\n§ " + raw + "\n")
        busy = true
        enable(false)
        thread(name = "prism-pdb-send", isDaemon = true) {
            val code = runCatching { active.run(raw) }.getOrElse {
                runOnUiThread { writeError("Could not send the command: " + (it.message ?: "closed") + "\n") }
                1
            }
            runOnUiThread {
                if (code != 0) writeError("\n[exit " + code + "]\n")
                busy = false
                enable(true)
            }
        }
    }

    private fun enable(enabled: Boolean) {
        input.isEnabled = enabled
        run.isEnabled = enabled
        run.alpha = if (enabled) 1f else 0.45f
        if (enabled) input.requestFocus()
    }

    private fun setState(text: String, failed: Boolean = false) {
        state.text = text
        state.setTextColor(if (failed) 0xFFFF6B6B.toInt() else 0xFF7DD3A0.toInt())
    }

    private fun write(text: String) {
        if (text.isEmpty()) return
        output.append(text)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun writeError(text: String) {
        if (text.isEmpty()) return
        val span = SpannableString(text)
        span.setSpan(
            ForegroundColorSpan(0xFFFF8A8A.toInt()),
            0,
            span.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        output.append(span)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_FP = "prism.pdb.fp"
        private const val EXTRA_NAME = "prism.pdb.name"
        private const val EXTRA_PLATFORM = "prism.pdb.platform"
        private const val EXTRA_IP = "prism.pdb.ip"

        fun intent(context: Context, target: Pdb.Target): Intent =
            Intent(context, PdbTerminalActivity::class.java).apply {
                putExtra(EXTRA_FP, target.fingerprint)
                putExtra(EXTRA_NAME, target.name)
                putExtra(EXTRA_PLATFORM, target.platform)
                putExtra(EXTRA_IP, target.ip)
            }
    }
}
