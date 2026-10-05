package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.notifications.NotificationHistory

/**
 * Capturing the notifications this machine receives. PHASE 110.
 *
 * ## Three platforms, three different answers, and one of them is "no"
 *
 * Android has `NotificationListenerService`: an app the user grants access to sees every notification on
 * the device. There is no single desktop equivalent, and pretending otherwise would be the worst outcome
 * -- a history page that looks like it is recording everything and is recording almost nothing.
 *
 *  - **LINUX: YES.** Notifications travel over D-Bus as `org.freedesktop.Notifications.Notify` calls, and
 *    the session bus can be monitored. [startLinuxMonitor] watches it and records what passes.
 *  - **WINDOWS: NO, AND THIS IS NOT A GAP TO FILL LATER.** `UserNotificationListener` exists and would do
 *    exactly this, and it requires a PACKAGE IDENTITY -- the process must be a signed MSIX with a declared
 *    `userNotificationListener` capability. Prism ships as a jpackage MSI, which installs files and gives
 *    no identity, so the API refuses at the access-request stage rather than returning nothing. Reaching
 *    it would mean changing how Prism is packaged and distributed, which is a different decision from a
 *    missing feature.
 *  - **macOS: NO.** There has never been a public API for reading other applications' notifications. The
 *    database under `~/Library/Group Containers` is private, undocumented and read by scripts that break
 *    with each release; Prism will not ship that.
 *
 * ## What every platform DOES capture
 *
 * PRISM'S OWN NOTIFICATIONS. [recordOwn] is called by `DesktopNotifier` on every platform, so the history
 * is never empty and never misleading: on Windows and macOS it is a complete record of what Prism told
 * you, and it says that is what it is. That is worth having on its own -- a Nebula post, a mesh transfer
 * finishing, a payment arriving, all searchable after the balloon has gone.
 *
 * ## Why the monitor is a subprocess and not a D-Bus library
 *
 * `dbus-monitor` is part of the same package as the bus itself, so it is present wherever the bus is. The
 * JVM alternatives are JNI bindings to libdbus or a pure-Java reimplementation of the wire protocol --
 * a native dependency or a protocol implementation, to read a stream of text that a shipped tool already
 * prints. The subprocess is the smaller thing by a wide margin, and if it is absent that is reported
 * rather than crashed on.
 */
object NotificationCapture {

    private const val TAG = "PrismNotifyCapture"

    /** Prism's own package name in the history, so its entries group together. */
    const val OWN_PACKAGE = "com.prism.desktop"

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val windows = os.contains("win")
    private val mac = os.contains("mac")

    @Volatile
    var monitoring = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var monitor: Process? = null

    /** What this platform can do, in a sentence for the page and the diagnostics. */
    fun capability(): String = when {
        windows ->
            "Only Prism's own notifications. Reading other applications' notifications on Windows needs " +
                "UserNotificationListener, which requires a packaged app identity (a signed MSIX with the " +
                "userNotificationListener capability). Prism installs from an MSI, which gives no identity, " +
                "so the API refuses outright -- this is a packaging decision, not a missing feature."

        mac ->
            "Only Prism's own notifications. macOS has never had a public API for reading other " +
                "applications' notifications, and the private database behind Notification Center is " +
                "undocumented and changes between releases."

        else -> if (monitoring) {
            "Everything on the session bus, plus Prism's own."
        } else {
            "Prism's own. System-wide capture needs dbus-monitor" +
                (if (lastError.isNotBlank()) " -- " + lastError else "") + "."
        }
    }

    /** Whether system-wide capture is even possible here. */
    fun systemWidePossible(): Boolean = !windows && !mac

    // ── Prism's own ────────────────────────────────────────────────────────

    /**
     * Records a notification Prism itself raised.
     *
     * Called from `DesktopNotifier.notify`, INCLUDING when there is no tray to show it on. A notification
     * that could not be displayed still happened, and the history is then the only trace of it.
     */
    fun recordOwn(channel: String, title: String, body: String) {
        runCatching {
            NotificationHistory.record(
                packageName = OWN_PACKAGE,
                appLabel = "Prism",
                title = title,
                text = body,
                // The channel goes in the key rather than the label, so history from one subsystem can be
                // cleared without touching the rest and two channels do not collapse into each other.
                key = channel,
            )
        }
    }

    // ── Linux ──────────────────────────────────────────────────────────────

    /**
     * Starts watching the session bus, on Linux.
     *
     * Returns false with a reason in [lastError] on any other platform or when the tool is absent. Safe to
     * call more than once.
     */
    fun start(): Boolean {
        if (monitoring) return true
        if (!systemWidePossible()) {
            lastError = "not available on this platform"
            return false
        }
        if (!PrismSettings.getNotificationHistoryEnabled()) {
            lastError = "notification history is switched off in settings"
            return false
        }
        return startLinuxMonitor()
    }

    fun stop() {
        monitor?.destroy()
        monitor = null
        monitoring = false
    }

    /**
     * The monitor itself.
     *
     * THE FILTER IS PASSED TO dbus-monitor RATHER THAN APPLIED HERE, so the bus only sends the calls that
     * matter. Monitoring everything and discarding most of it would put every method call on the session
     * bus -- and there are thousands a minute on a busy desktop -- through this parser.
     */
    private fun startLinuxMonitor(): Boolean = runCatching {
        val process = ProcessBuilder(
            "dbus-monitor",
            "--session",
            "interface='org.freedesktop.Notifications',member='Notify'",
        ).redirectErrorStream(true).start()

        monitor = process
        monitoring = true
        lastError = ""

        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines -> consume(lines) }
            }.onFailure { lastError = it.message.orEmpty() }
            monitoring = false
        }, "dbus-notification-monitor").apply { isDaemon = true }.start()

        PrismPlatform.log.info(TAG, "Watching the session bus for notifications.")
        true
    }.getOrElse {
        lastError = "dbus-monitor could not be started: " + (it.message ?: it::class.java.simpleName)
        monitoring = false
        PrismPlatform.log.warn(TAG, lastError)
        false
    }

    /**
     * Parses `dbus-monitor`'s output.
     *
     * ## The shape being read
     *
     * A Notify call prints as a header line then its arguments in order, one per line:
     *
     *     method call ... interface=org.freedesktop.Notifications; member=Notify
     *        string "Thunderbird"      <- app name
     *        uint32 0                  <- replaces id
     *        string ""                 <- icon
     *        string "New message"      <- summary, which is the title
     *        string "From: somebody"   <- body
     *
     * SO THE PARSER COUNTS STRINGS RATHER THAN MATCHING PATTERNS. The app name is the first, the summary
     * the third and the body the fourth -- positional, because the strings themselves are arbitrary user
     * content and any content-based guess would be wrong on somebody's machine. The icon string is
     * skipped rather than trusted to be empty, since many applications do set it.
     *
     * A call that ends early -- fewer strings than expected -- is recorded with what arrived rather than
     * dropped, because a notification with a title and no body is perfectly normal.
     */
    private fun consume(lines: Sequence<String>) {
        var inCall = false
        val strings = mutableListOf<String>()

        fun flush() {
            if (strings.isEmpty()) return
            val app = strings.getOrNull(0).orEmpty()
            // Index 1 is the icon; the summary and body follow it.
            val title = strings.getOrNull(2).orEmpty()
            val body = strings.getOrNull(3).orEmpty()
            strings.clear()
            if (app.isBlank() && title.isBlank()) return
            // Prism's own go through recordOwn, so skipping them here stops each one being stored twice
            // with two different app labels.
            if (app.equals("Prism", ignoreCase = true)) return
            runCatching {
                NotificationHistory.record(
                    packageName = app.lowercase().replace(" ", "."),
                    appLabel = app.ifBlank { "unknown" },
                    title = title,
                    text = body,
                )
            }
        }

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("method call") -> {
                    // A new call begins, so whatever the last one collected is complete.
                    flush()
                    inCall = trimmed.contains("member=Notify")
                }

                inCall && trimmed.startsWith("string \"") -> {
                    strings += trimmed.removePrefix("string \"").removeSuffix("\"")
                }

                // Anything else inside a call -- uint32, array, dict entries -- is skipped, but must not
                // end the call: the hints dictionary sits between the body and the timeout.
                else -> Unit
            }
        }
        flush()
    }

    /** One line for the diagnostics page. */
    fun describe(): String = buildString {
        append(if (PrismSettings.getNotificationHistoryEnabled()) "recording" else "switched off")
        append(" · ")
        append(runCatching { NotificationHistory.count() }.getOrDefault(0))
        append(" kept · ")
        append(if (monitoring) "system-wide" else "Prism's own only")
    }
}
