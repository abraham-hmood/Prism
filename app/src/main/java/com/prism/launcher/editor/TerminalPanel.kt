package com.prism.launcher.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * A real shell, below the editor.
 *
 * ## It is a real shell, with real limits
 *
 * This runs `/system/bin/sh` as a child process with its streams piped, so commands genuinely
 * execute -- `ls`, `cat`, `grep`, `find`, pipes, redirection, shell scripts. What it is not is a
 * terminal emulator: there is no pty, because allocating one needs native code Android does not
 * expose. Programs that draw with cursor control (`vi`, `top`, `htop`) or that check for a tty and
 * change behaviour will not work properly. Everything that reads stdin and writes stdout does.
 *
 * It also runs as Prism's own user, not root, so it sees what the app sees: its own storage, plus
 * whatever the granted storage permissions reach.
 *
 * ## One shell per panel, kept alive
 *
 * A shell that restarted per command would lose the working directory, every exported variable and
 * any running job -- which is most of what a shell is for. The process lives until the panel is
 * closed, so `cd` sticks.
 */
class TerminalPanel(context: Context) : LinearLayout(context) {

    private val output = TextView(context)
    private val scroll = ScrollView(context)
    private val input = EditText(context)

    private var shell: Process? = null
    private var writer: BufferedWriter? = null

    /** Capped so a runaway command cannot grow the buffer until the process dies. */
    private val buffer = StringBuilder()

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))

        output.apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(IosUi.label(context))
            setTextIsSelectable(true)
            val pad = IosUi.dp(context, 8f)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(output)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = IosUi.fieldBackground(context)
        }
        inputRow.addView(TextView(context).apply {
            text = "$"
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.accent(context))
            val pad = IosUi.dp(context, 8f)
            setPadding(pad, 0, pad / 2, 0)
        })
        input.apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            hint = "command"
            background = null
            // No autocorrect or capitalisation: a shell is not prose, and both actively corrupt
            // commands as they are typed.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
        }
        inputRow.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Starts the shell in [workingDirectory].
     *
     * The directory is the point of the argument: a terminal that opens in a random place is a
     * terminal you have to `cd` out of every time, so the editor passes the folder or file the user
     * has open, and falls back to the app's own storage when nothing is.
     */
    fun start(workingDirectory: File) {
        if (shell?.isAlive == true) return

        append("Prism shell · ${workingDirectory.absolutePath}\n")
        append("Running as this app -- no root, and no pty, so full-screen programs will not draw.\n\n")

        runCatching {
            val builder = ProcessBuilder("/system/bin/sh")
                .directory(if (workingDirectory.isDirectory) workingDirectory else workingDirectory.parentFile)
                .redirectErrorStream(true)
            builder.environment()["HOME"] = context.filesDir.absolutePath
            builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
            builder.environment()["TERM"] = "dumb"       // honest: there is no terminal to emulate
            builder.environment()["PS1"] = "$ "

            val process = builder.start()
            shell = process
            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            drain(process)
        }.onFailure {
            append("Could not start a shell: ${it.message}\n")
            PrismLogger.logError("PrismEditor", "Terminal failed to start", it)
        }
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { shell?.destroy() }
        writer = null
        shell = null
    }

    fun isRunning(): Boolean = shell?.isAlive == true

    /** Types a command into the shell without the user having to. Used by Run and Task commands. */
    fun run(command: String) {
        append("$ $command\n")
        send(command)
    }

    fun clear() {
        buffer.setLength(0)
        output.text = ""
    }

    private fun submit() {
        val command = input.text?.toString().orEmpty()
        if (command.isBlank()) return
        input.setText("")
        append("$ $command\n")
        send(command)
    }

    private fun send(command: String) {
        val target = writer
        if (target == null || shell?.isAlive != true) {
            append("(the shell is not running)\n")
            return
        }
        // Off the main thread: a full pipe buffer blocks the write, and blocking here would freeze
        // the UI for as long as the command takes to read its input.
        Thread({
            runCatching {
                target.write(command)
                target.newLine()
                target.flush()
            }.onFailure { post { append("write failed: ${it.message}\n") } }
        }, "prism-shell-write").apply { isDaemon = true; start() }
    }

    /**
     * Pumps the shell's output into the view.
     *
     * Reading this is not optional even if nothing displayed it: a child process whose output nobody
     * consumes fills the pipe buffer and then blocks forever on its next write, which looks exactly
     * like a hung command.
     */
    private fun drain(process: Process) {
        Thread({
            runCatching {
                val reader = process.inputStream.bufferedReader()
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    val text = String(chunk, 0, read)
                    post { append(text) }
                }
            }
            post { append("\n[shell exited]\n") }
        }, "prism-shell-read").apply { isDaemon = true; start() }
    }

    private fun append(text: String) {
        buffer.append(text)
        if (buffer.length > MAX_BUFFER) {
            // Drop from the front: the newest output is the part being read.
            buffer.delete(0, buffer.length - MAX_BUFFER)
        }
        output.text = buffer
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_BUFFER = 200_000
    }
}
