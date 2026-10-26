package com.prism.launcher.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.prism.launcher.PrismLogger
import com.prism.launcher.trusted.TrustedSharing

/**
 * Watches the system clipboard and feeds everything copied into the history.
 *
 * ## What Android allows, and what it does not
 *
 * Since Android 10 a background app may not read the clipboard. The listener below is still
 * registered on every version, but on a modern phone it only fires while Prism is focused or is
 * the default input method -- `onPrimaryClipChanged` from another app's copy is not delivered to
 * a launcher sitting in the background. So this also re-reads the clipboard every time the
 * launcher is resumed, which is the moment the user comes back from the app they copied out of.
 *
 * THE SAME RULE IS WHY THE LISTENER DELIVERS INSTANTLY WHEN PRISM WRITER IS THE KEYBOARD. The
 * clipboard service checks the default IME by PACKAGE, so every process in this app may read and
 * every registered listener is called, focused or not. [ClipboardWatchService] exists to keep
 * this process alive for exactly that case; when the keyboard is someone else's, that service
 * refuses to run and this falls back to the resume read above.
 *
 * ## Why the listener and the resume read both exist
 *
 * The listener catches copies made INSIDE Prism (the editor, a message, a password field in a
 * settings dialog) immediately, and the resume read catches everything else. Relying on either
 * alone would miss half the cases on a modern phone.
 *
 * ## Why writes from Prism are announced
 *
 * When the user taps an entry in the history, Prism writes that text back to the clipboard. The
 * listener sees its own write, and without [markWritten] it would record the item a second time
 * and move it to the top on every tap. The mark is the text rather than a flag because a flag
 * would also swallow a genuine copy of the same string that followed.
 */
object ClipboardCapture {

    private const val TAG = "PrismClipboard"

    private lateinit var appContext: Context

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** The last text this object saw or wrote, so the same content is not recorded twice. */
    @Volatile
    private var lastText: String = ""

    private val main = Handler(Looper.getMainLooper())

    fun install(context: Context) {
        appContext = context.applicationContext

        // ALREADY WATCHING: keep the baseline. Re-baselining here would swallow the first copy
        // that happened between the app starting and the watcher service attaching, because the
        // service calls install() a second time.
        if (listener != null) return

        val manager = manager() ?: return
        val added = ClipboardManager.OnPrimaryClipChangedListener { read(announce = true) }
        listener = added
        runCatching { manager.addPrimaryClipChangedListener(added) }
            .onFailure { PrismLogger.logWarning(TAG, "Could not watch the clipboard: " + it.message) }

        // A baseline, so the text that happened to be on the clipboard when the process started
        // is not recorded as something the user just copied. It is still the active system
        // clipboard; the history begins with what happens next.
        lastText = currentText().orEmpty()
    }

    /**
     * Re-reads the clipboard. Called when the launcher resumes, which on Android 10+ is the only
     * reliable moment a background-started app can see what another app copied.
     */
    fun sync() {
        read(announce = true)
    }

    /** Records a copy made inside Prism without waiting for the listener to fire. */
    fun recordNow(text: String) {
        if (text.isBlank()) return
        lastText = text
        TrustedSharing.publishText(text)
    }

    /**
     * Tells this object that Prism itself wrote [text].
     *
     * The write is still recorded and shared -- the user did choose it -- but the listener's echo
     * must not record it a second time.
     */
    fun markWritten(text: String) {
        lastText = text
    }

    /** Puts text on the system clipboard. Used by the history screen. */
    fun writeText(text: String): Boolean {
        val manager = manager() ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("Prism clipboard", text))
            markWritten(text)
            true
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not write the clipboard: " + it.message)
            false
        }
    }

    private fun read(announce: Boolean) {
        val text = currentText() ?: return
        if (text.isBlank() || text == lastText) return
        lastText = text
        // publishText records locally first, so history works with no wallet and no paired device;
        // the share is a no-op when there are no recipients.
        val recipients = if (announce) TrustedSharing.publishText(text) else 0
        if (recipients > 0) {
            PrismLogger.logInfo(TAG, "Shared a copied text with $recipients device(s)")
        }
    }

    private fun currentText(): String? = runCatching {
        val clip = manager()?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0)?.coerceToText(appContext)?.toString()
    }.getOrNull()

    private fun manager(): ClipboardManager? =
        (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)

    /** Runs [block] on the main thread, which is where the clipboard service must be touched. */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
