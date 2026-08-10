package com.prism.desktop

import com.prism.core.PrismLog
import com.prism.core.PrismPlatform
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop diagnostics: everything to a file, only problems to the console.
 *
 * The default [com.prism.core.ConsoleLog] writes every level to stderr, which is right for a
 * core with no platform installed and wrong here. Commands like `selftest` already print the
 * run's own log as part of their output, so an unfiltered mirror on stderr duplicates every
 * line -- and because stderr and stdout interleave unpredictably, the duplicate lands in the
 * middle of the report rather than beside it.
 *
 * So this is the desktop counterpart to Android's `PrismLogger`: a durable record on disk with
 * the same tagged format, and a console that stays quiet unless something is actually wrong.
 */
class DesktopLog(private val quiet: Boolean = true) : PrismLog {

    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val file: File by lazy {
        File(PrismPlatform.host.dataDir(), "logs").apply { mkdirs() }
            .resolve("prism.log")
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val line = "${LocalDateTime.now().format(stamp)} $level $tag: $message"
        synchronized(this) {
            runCatching {
                file.appendText(line + "\n")
                throwable?.let { file.appendText(it.stackTraceToString() + "\n") }
            }
        }
        // WARN and above always surface. Below that, only when the user asked for noise.
        if (!quiet || level == "WARN" || level == "ERROR") {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        }
    }

    override fun debug(tag: String, message: String) = write("DEBUG", tag, message)
    override fun info(tag: String, message: String) = write("INFO", tag, message)
    override fun warn(tag: String, message: String) = write("WARN", tag, message)
    override fun success(tag: String, message: String) = write("OK", tag, message)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        write("ERROR", tag, message, throwable)

    override fun flushBlocking() = System.err.flush()

    fun path(): File = file
}
