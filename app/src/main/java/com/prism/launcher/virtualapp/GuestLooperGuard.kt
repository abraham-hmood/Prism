package com.prism.launcher.virtualapp

import android.os.Handler
import android.os.Looper
import com.prism.launcher.PrismLogger

/**
 * Keeps a virtualized app alive through the failures that come from it not really being itself.
 *
 * ## Why apps were crashing
 *
 * A guest runs under Prism's uid while calling itself by its own package name. Most of the system
 * calls that would object are rewritten by [SystemServiceHook], but not every service is the
 * system's. Google Play services is an ordinary app with its own binder interfaces, and it checks the
 * caller itself: Firebase Analytics -- in Acorns, Albert and a great many others -- asks it to log an
 * event as `com.acorns.android`, Play services sees the call come from Prism's uid, and answers
 *
 * ```
 * SecurityException: Unknown calling package name 'com.acorns.android'.
 * ```
 *
 * on the analytics library's Handler, on the main thread. Nothing catches it there, so the looper
 * dies, the process dies, and on the full-screen host Android relaunches the activity -- which
 * reached the same call and died again. That is the "Opening app…" loop.
 *
 * ## What this does
 *
 * Runs the main looper inside a catch. A throwable that is one of these identity refusals, or comes
 * out of Play services or Firebase code, is logged and recorded in the launch report, and the looper
 * is entered again: the message that failed is lost (an analytics event, usually), and the app keeps
 * running. Anything else is rethrown and crashes exactly as it would have, with the same report --
 * hiding a real bug in the app would only move the failure somewhere harder to read.
 *
 * Re-entering `Looper.loop()` from inside a message is legal; Android logs one warning the first
 * time, and the outer loop simply never gets control back.
 */
object GuestLooperGuard {

    private const val TAG = "PrismVirtualApp"

    @Volatile private var installed = false

    /** How many failures were survived, so a report can say the app is limping rather than fine. */
    @Volatile var survived = 0
        private set

    /** Installs the guard on the main looper. Idempotent; call from the main thread or any other. */
    fun install() {
        if (installed) return
        installed = true
        Handler(Looper.getMainLooper()).post { guardedLoop() }
    }

    private fun guardedLoop() {
        while (true) {
            try {
                Looper.loop()
                return // The looper was quit: the process is going away on purpose.
            } catch (error: Throwable) {
                if (!survivable(error)) throw error
                survived++
                PrismLogger.logWarning(
                    TAG,
                    "Kept the app running after it was refused by another service " +
                        "(${error.javaClass.simpleName}: ${error.message}); the message that failed was dropped",
                )
            }
        }
    }

    /**
     * Whether [error] is a consequence of virtualization that the app can live without, rather than
     * a fault in the app itself.
     */
    fun survivable(error: Throwable): Boolean {
        if (error is Error) return false // Out of memory, a linkage failure: not ours to paper over.
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            if (isIdentityRefusal(cause) || fromPlayServices(cause)) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    /**
     * The refusals a guest gets for not being the uid it names: "Unknown calling package", "does not
     * belong to uid", "not allowed to send as package", "uid ... does not own package".
     */
    private fun isIdentityRefusal(error: Throwable): Boolean {
        if (error !is SecurityException) return false
        val message = error.message.orEmpty().lowercase()
        return IDENTITY_PHRASES.any { it in message }
    }

    /** Thrown from Play services or Firebase code, which a guest cannot satisfy under Prism's uid. */
    private fun fromPlayServices(error: Throwable): Boolean {
        val kind = error is SecurityException || error is IllegalStateException ||
            error is UnsupportedOperationException || error is android.os.RemoteException
        if (!kind) return false
        return error.stackTrace.take(16).any { frame ->
            frame.className.startsWith("com.google.android.gms.") ||
                frame.className.startsWith("com.google.firebase.") ||
                // Dynamite modules are loaded from Play services' own APK and are obfuscated; the
                // file name is what still says where they came from.
                frame.fileName.orEmpty().contains("com.google.android.gms")
        }
    }

    private val IDENTITY_PHRASES = listOf(
        "unknown calling package",
        "does not belong to",
        "not allowed to send as package",
        "does not own package",
        "package name mismatch",
        "calling package",
    )
}

/**
 * Gives the virtualized apps' process a WebView data folder of its own.
 *
 * Every process of an app shares one WebView folder by default, and Chromium allows only one
 * process to use it at a time. Prism's main process -- its browser page -- already holds it, so the
 * first WebView a guest opened (TikTok opens one at start) died with "Using WebView from more than
 * one process at once with the same data directory is not supported". Android's own answer is a
 * per-process suffix, which must be set before anything in the process touches WebView: so it is
 * set as each entry point of `:virtualapp` starts. It is one folder for all guests, because the
 * suffix cannot change once set and the process hosts one app after another.
 */
object GuestWebView {

    @Volatile private var done = false

    fun isolate() {
        if (done) return
        done = true
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        runCatching { android.webkit.WebView.setDataDirectorySuffix("virtualapp") }
            .onFailure { PrismLogger.logWarning("PrismVirtualApp", "Could not give guests their own WebView folder: ${it.message}") }
    }
}
