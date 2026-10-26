package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.FileOps
import com.prism.launcher.clipboard.ClipboardHistory
import com.prism.launcher.trusted.TrustedSharing
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Watches this machine's clipboard and shares it, the desktop half of the phone's capture.
 *
 * ## Why polling
 *
 * AWT has no clipboard change listener. It has [java.awt.datatransfer.ClipboardOwner], which is
 * only told about writes made THROUGH that owner, and an OS-level copy in another application
 * never reaches it. A one-second poll is the smallest thing that sees those, and it is cheap: two
 * clipboard reads and two string comparisons, on a thread of its own.
 *
 * ## Why files are here too
 *
 * A copy in the desktop's file explorer and a copy in the OS file manager are the same thing to a
 * user, and the second never passes through Prism's code. Reading `javaFileListFlavor` means both
 * arrive in the history and both are shared, and pasting either in the explorer places the real
 * file rather than a path string.
 *
 * ## Why writes from Prism are marked
 *
 * The poll would otherwise see the text a history tap just put on the clipboard and share it
 * again, which turns one paste into a broadcast and, with two devices doing it, into a loop.
 */
object DesktopClipboard {

    private const val TAG = "PrismClipboard"

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "prism-clipboard").apply { isDaemon = true }
    }

    @Volatile
    private var lastText: String = ""

    @Volatile
    private var lastFiles: String = ""

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true

        TrustedSharing.clipboardReader = {
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard
                    .getData(DataFlavor.stringFlavor) as? String
            }.getOrNull()
        }

        // A baseline, so whatever was on the clipboard before Prism started is not announced to
        // every paired device as a fresh copy.
        poll(record = false)

        executor.scheduleWithFixedDelay(
            { runCatching { poll(record = true) } },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    /** Puts text on the system clipboard. Used when a history entry is chosen. */
    fun writeText(text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        lastText = text
        true
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "Could not write the clipboard: " + it.message)
        false
    }

    /**
     * Marks text Prism itself put on the clipboard.
     *
     * Called when an incoming share is placed there, so the poll does not send it straight back to
     * the device it came from.
     */
    fun markWritten(text: String) {
        lastText = text
    }

    /** Makes an entry the active one and prepares the right local clipboard for it. */
    fun use(entry: ClipboardHistory.Entry): String {
        ClipboardHistory.setActive(entry.id)
        return when (entry.kind) {
            ClipboardHistory.Kind.TEXT -> {
                if (writeText(entry.text)) "Copied — paste it anywhere" else "Could not write the clipboard"
            }
            ClipboardHistory.Kind.FILE -> {
                if (entry.remote) {
                    entry.name + " will be downloaded when you paste it in the file explorer"
                } else {
                    val file = File(entry.path)
                    if (!file.exists()) {
                        "That item is no longer on this device"
                    } else {
                        when (entry.mode) {
                            ClipboardHistory.Mode.CUT -> FileOps.cut(listOf(file))
                            ClipboardHistory.Mode.COPY -> FileOps.copy(listOf(file))
                        }
                        "${if (entry.directory) "Folder" else "File"} ready — paste it in the explorer"
                    }
                }
            }
        }
    }

    private fun poll(record: Boolean) {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return

        val files = runCatching {
            @Suppress("UNCHECKED_CAST")
            clipboard.getData(DataFlavor.javaFileListFlavor) as? List<Any?>
        }.getOrNull()

        if (!files.isNullOrEmpty()) {
            val paths = files.mapNotNull { (it as? File)?.absolutePath }
            val key = paths.joinToString("|")
            if (key.isNotBlank() && key != lastFiles) {
                lastFiles = key
                lastText = ""
                if (record) {
                    files.filterIsInstance<File>().forEach { file ->
                        runCatching { TrustedSharing.publishFile(file, cut = false) }
                    }
                }
            }
            return
        }

        val text = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (text.isNullOrBlank() || text == lastText) return
        lastText = text
        lastFiles = ""
        if (record) {
            runCatching { TrustedSharing.publishText(text) }
                .onFailure { PrismPlatform.log.warn(TAG, "Could not share a copy: " + it.message) }
        }
    }
}
