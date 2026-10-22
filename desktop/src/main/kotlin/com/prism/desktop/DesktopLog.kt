package com.prism.desktop

import com.prism.core.PrismLog
import com.prism.core.PrismPlatform
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

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

    enum class Level { INFO, WARN, ERROR, SUCCESS, DEBUG }

    data class Entry(
        val id: Long,
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val ids = AtomicLong()
    private val history = ArrayList<Entry>()
    private var historyLoaded = false
    private var loadedFileLength = -1L
    private var loadedFileModified = -1L
    private val mutableUpdates = MutableSharedFlow<Entry>(extraBufferCapacity = 512)
    val updates = mutableUpdates.asSharedFlow()

    private val file: File by lazy {
        File(PrismPlatform.host.dataDir(), "logs").apply { mkdirs() }
            .resolve("prism.log")
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(stamp)
        val detail = message + (throwable?.let { "\n" + it.stackTraceToString() } ?: "")
        val line = "[$timestamp] [$level] [$tag] $message"
        val entry = synchronized(this) {
            loadHistoryLocked()
            Entry(ids.incrementAndGet(), timestamp, parseLevel(level), tag, detail).also { created ->
                history += created
                runCatching {
                    file.appendText(line + (throwable?.let { "\n" + it.stackTraceToString() } ?: "") + "\n")
                    loadedFileLength = file.length()
                    loadedFileModified = file.lastModified()
                }
            }
        }
        mutableUpdates.tryEmit(entry)
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

    /** Every persisted and current-process entry, oldest first. */
    @Synchronized
    fun entries(): List<Entry> {
        loadHistoryLocked()
        return history.toList()
    }

    @Synchronized
    fun clear() {
        history.clear()
        historyLoaded = true
        runCatching { file.writeText("") }
    }

    private fun loadHistoryLocked() {
        val currentLength = runCatching { file.length() }.getOrDefault(-1L)
        val currentModified = runCatching { file.lastModified() }.getOrDefault(-1L)
        if (historyLoaded && currentLength == loadedFileLength && currentModified == loadedFileModified) return
        historyLoaded = true
        history.clear()
        ids.set(0L)
        if (!file.isFile) return
        var lastIndex = -1
        runCatching {
            file.forEachLine { line ->
                val entry = parseLine(line)
                if (entry != null) {
                    history += entry.copy(id = ids.incrementAndGet())
                    lastIndex = history.lastIndex
                } else if (lastIndex >= 0) {
                    history[lastIndex] = history[lastIndex].copy(
                        message = history[lastIndex].message + "\n" + line,
                    )
                }
            }
        }.onFailure {
            history.clear()
            System.err.println("Could not read Prism log: " + it.message)
        }
        loadedFileLength = runCatching { file.length() }.getOrDefault(-1L)
        loadedFileModified = runCatching { file.lastModified() }.getOrDefault(-1L)
    }

    private fun parseLine(line: String): Entry? {
        val modern = MODERN_LINE.matchEntire(line)
        if (modern != null) {
            return Entry(
                0,
                modern.groupValues[1],
                parseLevel(modern.groupValues[2]),
                modern.groupValues[3],
                modern.groupValues[4],
            )
        }
        val legacy = LEGACY_LINE.matchEntire(line) ?: return null
        return Entry(
            0,
            LocalDate.now().toString() + " " + legacy.groupValues[1],
            parseLevel(legacy.groupValues[2]),
            legacy.groupValues[3],
            legacy.groupValues[4],
        )
    }

    private fun parseLevel(value: String): Level = when (value.uppercase()) {
        "WARN", "WARNING" -> Level.WARN
        "ERROR", "FAIL" -> Level.ERROR
        "OK", "SUCCESS" -> Level.SUCCESS
        "DEBUG" -> Level.DEBUG
        else -> Level.INFO
    }

    private companion object {
        val MODERN_LINE = Regex("^\\[(.+)] \\[(INFO|WARN|ERROR|OK|SUCCESS|DEBUG)] \\[(.*?)] (.*)$")
        val LEGACY_LINE = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (INFO|WARN|ERROR|OK|SUCCESS|DEBUG) (.*?): (.*)$")
    }
}
