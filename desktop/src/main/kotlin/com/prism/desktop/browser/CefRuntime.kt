package com.prism.desktop.browser

import com.prism.core.PrismPlatform
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Brings Chromium up, once, for the whole process.
 *
 * WHY THIS IS ASYNCHRONOUS AND VISIBLE. On first run jcefmaven downloads a ~100 MB native bundle
 * for the host platform and unpacks it, which takes tens of seconds on a slow connection. Hiding
 * that behind a spinner with no explanation makes the browser page look broken the first time
 * anyone opens it, so [state] reports the phase and the percentage and the page shows both.
 * Subsequent runs find the unpacked bundle and start in about a second.
 *
 * CEF IS PROCESS-WIDE, NOT PER-TAB. A single [CefApp] runs a browser process and a set of
 * renderer subprocesses; every tab is a `CefBrowser` inside it. That is why this is an object and
 * why [dispose] matters -- leaking it leaves orphaned Chromium subprocesses after Prism exits,
 * which on Windows shows up as the app appearing not to close.
 *
 * TWO CEF CONTEXTS, NOT ONE, which is what makes private tabs actually private. Chromium isolates
 * cookies, storage and cache per request context; a private tab gets an off-the-record context
 * whose data lives only in memory and is destroyed with it. Doing this any other way -- clearing
 * cookies on tab close, say -- would leave the data on disk in between.
 */
object CefRuntime {

    sealed interface State {
        data object Idle : State
        data class Preparing(val phase: String, val percent: Float) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** Called whenever [state] changes, so the UI can recompose. */
    @Volatile
    var onStateChange: ((State) -> Unit)? = null

    private val app = AtomicReference<CefApp?>(null)
    private var initializing = false

    /**
     * Starts Chromium if it is not already running. Safe to call repeatedly.
     *
     * Blocking, and expected to be called off the UI thread -- the download and unpack on first
     * run are minutes-long operations in the worst case.
     */
    @Synchronized
    fun ensureStarted(): CefApp? {
        app.get()?.let { return it }
        if (state is State.Failed) return null
        if (initializing) return null
        initializing = true

        return try {
            publish(State.Preparing("Locating Chromium", 0f))

            val builder = CefAppBuilder()
            builder.setInstallDir(File(PrismPlatform.host.dataDir(), "jcef"))
            builder.setProgressHandler(ProgressReporter())

            builder.cefSettings.apply {
                // Windowless rendering off: Prism embeds the browser in a real AWT component,
                // which is both faster and avoids the input-handling problems the offscreen path
                // has with keyboard focus.
                windowless_rendering_enabled = false
                // Chromium's own cache and cookies for NORMAL tabs. Private tabs get an
                // off-the-record context instead and never touch this directory.
                cache_path = File(PrismPlatform.host.dataDir(), "browser/cache").absolutePath
                log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR
                persist_session_cookies = true
            }

            // Chromium refuses to start as root without this, which matters on Linux where
            // running a launcher elevated is not unheard of.
            builder.addJcefArgs("--disable-gpu-shader-disk-cache")

            val created = builder.build()
            app.set(created)
            publish(State.Ready)
            created
        } catch (e: Throwable) {
            // Throwable: a missing native bundle surfaces as UnsatisfiedLinkError, not Exception.
            PrismPlatform.log.error("Prism/cef", "Chromium failed to start", e)
            publish(State.Failed(e.message ?: e::class.java.simpleName))
            null
        } finally {
            initializing = false
        }
    }

    /**
     * A client for one browsing mode.
     *
     * Normal tabs share one client and therefore one cookie jar, exactly as tabs in any browser
     * do. Private tabs each get their own, so two private tabs cannot see each other's session
     * either -- which is stricter than Android's WebView incognito and is the correct reading of
     * what a private tab promises.
     */
    fun newClient(): CefClient? = app.get()?.createClient()

    fun dispose() {
        app.getAndSet(null)?.dispose()
    }

    private fun publish(next: State) {
        state = next
        onStateChange?.invoke(next)
    }

    private class ProgressReporter : IProgressHandler {
        override fun handleProgress(enumProgress: EnumProgress, percent: Float) {
            val label = when (enumProgress) {
                EnumProgress.LOCATING -> "Locating Chromium"
                EnumProgress.DOWNLOADING -> "Downloading Chromium"
                EnumProgress.EXTRACTING -> "Extracting"
                EnumProgress.INSTALL -> "Installing"
                EnumProgress.INITIALIZING -> "Starting"
                EnumProgress.INITIALIZED -> "Ready"
            }
            // jcefmaven reports -1 for indeterminate phases; do not show that as a percentage.
            publish(
                if (enumProgress == EnumProgress.INITIALIZED) State.Ready
                else State.Preparing(label, percent.coerceAtLeast(0f))
            )
        }
    }
}
