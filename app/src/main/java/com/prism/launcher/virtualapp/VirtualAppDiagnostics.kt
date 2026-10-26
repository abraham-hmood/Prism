package com.prism.launcher.virtualapp

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.virtualization.VirtualizedAppVault
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a virtualized launch was doing when it stopped, written where the launcher can read it.
 *
 * ## The gap this closes
 *
 * A virtualized app runs in `:virtualapp`, and when its process dies the launcher is told one thing
 * by the system: the binder went away. Everything that actually happened -- the exception, the
 * thread, the stage of startup -- died with the process, so the page could only say "the app's
 * process stopped", which is a restatement of the symptom rather than a reason.
 *
 * This writes two small plain-text files into the app's vault directory as the launch progresses and
 * if the guest throws:
 *
 *  - `launch-stage.txt`, replaced at every step, so after a kill the last step names what it was
 *    doing -- loading the APK, building its Application, hosting its activity, waiting for its first
 *    frame;
 *  - `launch-crash.txt`, written by an uncaught-exception handler that runs before the process is
 *    allowed to die.
 *
 * Deliberately NOT inside `live/`: that directory is the app's own data, sealed on the way out, and
 * diagnostics about the launcher's own machinery do not belong in the app's sandbox or in its
 * encrypted archive.
 */
/**
 * One line naming what went wrong, with the cause chain when there is one.
 *
 * Every failure path in this feature ends in this function rather than in `it.message`, because a
 * message can be null -- `InvocationTargetException` and many framework exceptions have no message
 * at all -- and "null" tells nobody anything. The class name always exists, so it is always shown.
 */
internal fun describe(error: Throwable?): String {
    if (error == null) return "no reason was reported"

    val text = buildString {
        append(error.javaClass.simpleName)
        error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }

    val cause = error.cause
    if (cause != null && cause !== error) {
        val also = buildString {
            append(cause.javaClass.simpleName)
            cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
        return "$text (caused by $also)"
    }
    return text
}

object VirtualAppDiagnostics {

    private const val TAG = "PrismVirtualApp"

    private const val STAGE_FILE = "launch-stage.txt"
    private const val CRASH_FILE = "launch-crash.txt"

    /** The package whose launch is in progress, for the crash handler to name. */
    @Volatile
    private var currentPackage: String? = null

    /** True when the guest's own code is running, rather than Prism's loader. */
    @Volatile
    private var guestCodeRunning = false

    private var handlerInstalled = false

    /** Records the step the launch is on. Cheap enough to call at every stage. */
    fun stage(context: Context, packageName: String, step: String) {
        currentPackage = packageName
        write(context, packageName, STAGE_FILE, "$step\n")
    }

    /**
     * Records that guest code is now running.
     *
     * Kept separate from [stage] because it changes how a crash is described: an exception from
     * inside the app is the app's problem and is reported as such, while one from Prism's loader is
     * reported as a limitation of the virtualization layer. Both carry the same stack; only the
     * sentence in front of it differs.
     */
    fun guestCode(context: Context, packageName: String, step: String) {
        guestCodeRunning = true
        stage(context, packageName, step)
    }

    /** Notes that the launch got far enough to draw, which is the only real definition of success. */
    fun drawn(context: Context, packageName: String) {
        write(context, packageName, STAGE_FILE, "drawing the first frame\n")
    }

    /**
     * Installs the handler that captures the guest's uncaught exceptions.
     *
     * Chains to whatever handler was there rather than replacing it, so the platform still reports
     * and kills the process exactly as it would have. Installed from both hosts, because a launch
     * can start in either one and the process may already be up from the other.
     */
    fun installCrashHandler(context: Context) {
        if (handlerInstalled) return
        handlerInstalled = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val packageName = currentPackage
            if (packageName != null) {
                runCatching {
                    val source = if (guestCodeRunning) {
                        "$packageName threw while running"
                    } else {
                        "Prism failed while starting $packageName"
                    }
                    write(
                        app,
                        packageName,
                        CRASH_FILE,
                        "$source on thread '${thread.name}'\n\n" + error.stackTraceToString(),
                    )
                }
                PrismLogger.logError(TAG, "Uncaught in ${thread.name} while hosting $packageName", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Everything known about the last launch of [packageName], or null if there is nothing. */
    fun read(context: Context, packageName: String): String? {
        val crash = readFile(context, packageName, CRASH_FILE)
        val stage = readFile(context, packageName, STAGE_FILE)
        if (crash == null && stage == null) return null

        return buildString {
            if (crash != null) appendLine(crash.trim())
            if (stage != null) {
                if (crash != null) appendLine()
                append("Last step reached: ").appendLine(stage.trim())
            }
        }.trim()
    }

    /** Forgets the previous launch, so a report always describes the run that just failed. */
    fun clear(context: Context, packageName: String) {
        currentPackage = packageName
        guestCodeRunning = false
        runCatching { file(context, packageName, CRASH_FILE).delete() }
        runCatching { file(context, packageName, STAGE_FILE).delete() }
    }

    private fun write(context: Context, packageName: String, name: String, text: String) {
        runCatching {
            val target = file(context, packageName, name)
            target.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            target.writeText("$stamp $text")
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not write the launch report for $packageName: ${it.message}")
        }
    }

    private fun readFile(context: Context, packageName: String, name: String): String? = runCatching {
        val target = file(context, packageName, name)
        if (target.isFile) target.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    private fun file(context: Context, packageName: String, name: String): File =
        File(VirtualizedAppVault.appDir(context, packageName), name)
}
