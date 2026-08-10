package com.prism.launcher

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Terminal-style Logger for Prism.
 * Handles persistence to file, crash interception, and live UI updates.
 */
object PrismLogger {
    private const val TAG = "PrismLogger"
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Live UI feed (replays last 50 for when the UI opens)
    val logFlow = MutableSharedFlow<LogEntry>(replay = 50, extraBufferCapacity = 100)

    private var logFile: File? = null
    private var writer: java.io.BufferedWriter? = null
    private var pendingWrites = 0
    private var bytesSinceRotationCheck = 0
    private var consecutiveFailures = 0

    /** Set once file logging has failed repeatedly; logcat and the live feed continue. */
    @Volatile
    var fileLoggingDisabled = false
        private set

    private const val FLUSH_EVERY = 24
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024
    private const val ROTATION_CHECK_BYTES = 128 * 1024
    private const val MAX_FAILURES = 5

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    )

    enum class Level { INFO, WARN, ERROR, SUCCESS, DEBUG }

    fun init(context: Context) {
        val storage = Environment.getExternalStorageDirectory()
        val prismDir = File(storage, "Prism")
        if (!prismDir.exists()) prismDir.mkdirs()

        logFile = File(prismDir, "diagnostics.log")
        rotateIfNeeded()

        // Intercept Crashes globally
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val threadInfo = "Name: ${thread.name}, ID: ${thread.id}"
            logError("CRASH", "Uncaught exception in thread [$threadInfo]", throwable)
            // The process is about to die, so the buffered writer has to be pushed out NOW --
            // a crash report that is still sitting in a BufferedWriter when the process is
            // killed is a crash report that never existed.
            flushBlocking()
            // Still write to standard Logcat for system collectors
            Log.e(TAG, "FATAL EXCEPTION in thread [$threadInfo]", throwable)
            oldHandler?.uncaughtException(thread, throwable)
        }

        logInfo(TAG, "Prism Diagnostics Initialized. Logging to ${logFile?.absolutePath}")
    }

    /**
     * A [CoroutineExceptionHandler] that reports into diagnostics.
     *
     * The global uncaught-exception handler above does not see everything. A coroutine that
     * fails inside a scope built on SupervisorJob routes its exception to the scope's
     * CoroutineExceptionHandler, and if there isn't one the failure can be dropped entirely
     * rather than reaching the thread handler. Long-running background work -- training,
     * generation, mesh services -- is exactly where that silence is most expensive, so those
     * scopes install this.
     */
    fun coroutineHandler(tag: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
            logError(tag, "Unhandled exception in a background coroutine", throwable)
        }

    fun logInfo(tag: String, message: String) {
        append(Level.INFO, tag, message)
        Log.i(tag, message)
    }

    fun logDebug(tag: String, message: String) {
        append(Level.DEBUG, tag, message)
        Log.d(tag, message)
    }

    fun logWarning(tag: String, message: String) {
        append(Level.WARN, tag, message)
        Log.w(tag, message)
    }

    fun logSuccess(tag: String, message: String) {
        append(Level.SUCCESS, tag, message)
        Log.d(tag, "SUCCESS: $message")
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else message
        append(Level.ERROR, tag, fullMessage)
        Log.e(tag, fullMessage)
    }

    private fun append(level: Level, tag: String, message: String) {
        val entry = LogEntry(getTimestamp(), level, tag, message)
        scope.launch {
            logFlow.emit(entry)
            writeToFile(entry)
        }
    }

    /**
     * Appends one entry.
     *
     * Holds the writer open rather than reopening the file per line. The original version
     * constructed a FileWriter, wrote one line and closed it for every single log call, which
     * is roughly three syscalls plus a metadata update per message -- survivable at a handful
     * of messages a minute, not at the rate a training run produces.
     *
     * Synchronized because the emitting scope is Dispatchers.IO, which is a pool: without it,
     * two coroutines can interleave inside a shared BufferedWriter and produce spliced lines.
     */
    @Synchronized
    private fun writeToFile(entry: LogEntry) {
        if (fileLoggingDisabled) return
        try {
            val file = logFile ?: return
            val w = writer ?: run {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                FileWriter(file, true).let { java.io.BufferedWriter(it, 16 * 1024) }
                    .also { writer = it }
            }
            w.write("[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}\n")
            pendingWrites++
            bytesSinceRotationCheck += entry.message.length + 64

            // Errors and crashes are flushed immediately; everything else rides the buffer.
            // The whole point of a diagnostics log is to survive the failure being diagnosed,
            // and a process death takes the buffer with it.
            if (entry.level == Level.ERROR || pendingWrites >= FLUSH_EVERY) {
                w.flush()
                pendingWrites = 0
            }
            if (bytesSinceRotationCheck > ROTATION_CHECK_BYTES) {
                bytesSinceRotationCheck = 0
                rotateIfNeeded()
            }
            consecutiveFailures = 0
        } catch (e: Exception) {
            // Storage permission not granted, card unmounted, disk full. Retrying on every
            // subsequent line would burn real CPU for nothing, so give up after a few and keep
            // logcat and the live UI feed working.
            consecutiveFailures++
            closeWriterQuietly()
            if (consecutiveFailures >= MAX_FAILURES) {
                fileLoggingDisabled = true
                Log.e(TAG, "Disabling file logging after $consecutiveFailures failures", e)
            }
        }
    }

    /**
     * Caps the log's size.
     *
     * Without this the file grows without bound, which matters far more now that Nora logs
     * every epoch and every generation: an overnight training run alone can produce tens of
     * thousands of lines. One previous generation is kept, because the interesting event is
     * often just before the one you noticed.
     */
    @Synchronized
    private fun rotateIfNeeded() {
        try {
            val file = logFile ?: return
            if (!file.exists() || file.length() < MAX_LOG_BYTES) return
            closeWriterQuietly()
            val previous = File(file.parentFile, "diagnostics.1.log")
            if (previous.exists()) previous.delete()
            if (!file.renameTo(previous)) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
        }
    }

    /** Pushes anything buffered to disk. Safe to call from the crash handler. */
    @Synchronized
    fun flushBlocking() {
        try {
            writer?.flush()
            pendingWrites = 0
        } catch (e: Exception) {
            Log.e(TAG, "Log flush failed", e)
        }
    }

    private fun closeWriterQuietly() {
        try {
            writer?.close()
        } catch (e: Exception) {
            // Already broken; nothing to salvage.
        }
        writer = null
        pendingWrites = 0
    }

    private fun getTimestamp(): String = sdf.format(Date())
}
