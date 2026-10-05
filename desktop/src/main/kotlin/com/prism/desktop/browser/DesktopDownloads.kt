package com.prism.desktop.browser

import com.prism.core.PrismPlatform
import org.cef.browser.CefBrowser
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandler
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Files the browser downloads. PHASE 79's second half.
 *
 * ## Why Chromium does the fetching
 *
 * A download usually depends on everything the page already negotiated — cookies, a redirect chain, an
 * Authorization header, sometimes a POST. A separate HTTP client would have to reproduce all of it and
 * would get it wrong on exactly the links people care about. JCEF exposes a download handler, so the
 * transfer stays inside the engine that was already authenticated and this only decides where the file
 * lands and keeps the list.
 *
 * ## Why it does not ask where to put each file
 *
 * A save dialog per download is what a browser does; a launcher's browser saving into one obvious place
 * is less friction and is what the phone does. The folder is the platform's Downloads directory, so the
 * file is where every other application on the machine would look for it.
 *
 * ## What a "remove" means here
 *
 * DELETING THE FILE, as on Android. A downloads list that forgot an entry but left a hundred megabytes
 * on disk would be a list that lies about what is on the machine. Removing a downloaded SITE is a
 * different operation with different consequences — see MeshMirror.remove.
 */
object DesktopDownloads : CefDownloadHandler {

    private const val TAG = "PrismDownloads"

    data class Download(
        val id: Int,
        val name: String,
        val url: String,
        val path: String,
        val totalBytes: Long,
        val receivedBytes: Long,
        val done: Boolean,
        val cancelled: Boolean,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0) 0 else ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    private val active = ConcurrentHashMap<Int, Download>()
    private val callbacks = ConcurrentHashMap<Int, CefDownloadItemCallback>()

    /** Called as downloads progress, so a page can follow them. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun all(): List<Download> = active.values.sortedByDescending { it.id }

    fun directory(): File {
        val home = System.getProperty("user.home").orEmpty()
        val downloads = File(home, "Downloads")
        return (if (downloads.isDirectory) downloads else File(home)).apply { mkdirs() }
    }

    /** Cancels one in flight. */
    fun cancel(id: Int) {
        callbacks[id]?.cancel()
    }

    /**
     * Forgets an entry and deletes the file.
     *
     * The file first: if the delete fails the entry stays, so the list keeps pointing at something that
     * is genuinely still there rather than quietly losing track of it.
     */
    fun remove(id: Int): Boolean {
        val download = active[id] ?: return false
        val file = File(download.path)
        val gone = !file.exists() || file.delete()
        if (gone) {
            active.remove(id)
            callbacks.remove(id)
            onChanged?.invoke()
        }
        return gone
    }

    // ── CefDownloadHandler ─────────────────────────────────────────────────

    override fun onBeforeDownload(
        browser: CefBrowser?,
        item: CefDownloadItem?,
        suggestedName: String?,
        callback: CefBeforeDownloadCallback?,
    ): Boolean {
        val name = suggestedName?.takeIf { it.isNotBlank() } ?: "download"
        val target = uniqueFile(name)
        PrismPlatform.log.info(TAG, "Downloading " + name + " to " + target.absolutePath)
        // false for showDialog: see the class comment. The path is chosen here rather than asked for.
        callback?.Continue(target.absolutePath, false)
        // true: this handler took the download. Returning false would let Chromium fall back to its
        // own behaviour, which is the dialog this deliberately replaces.
        return true
    }

    override fun onDownloadUpdated(
        browser: CefBrowser?,
        item: CefDownloadItem?,
        callback: CefDownloadItemCallback?,
    ) {
        val download = item ?: return
        val id = download.id
        callback?.let { callbacks[id] = it }

        active[id] = Download(
            id = id,
            name = download.suggestedFileName.orEmpty().ifBlank { "download" },
            url = download.url.orEmpty(),
            path = download.fullPath.orEmpty(),
            totalBytes = download.totalBytes,
            receivedBytes = download.receivedBytes,
            done = download.isComplete,
            cancelled = download.isCanceled,
        )

        if (download.isComplete) {
            PrismPlatform.log.info(TAG, "Finished " + download.suggestedFileName)
            PrismPlatform.notifier.notify(
                channel = "downloads",
                id = id,
                title = "Download finished",
                body = download.suggestedFileName.orEmpty(),
            )
            callbacks.remove(id)
        }
        onChanged?.invoke()
    }

    /**
     * A name that is not already taken.
     *
     * Chromium would otherwise overwrite, and a second copy of a file silently replacing the first is
     * the kind of data loss nobody notices until they need the original.
     */
    private fun uniqueFile(name: String): File {
        val folder = directory()
        val candidate = File(folder, sanitize(name))
        if (!candidate.exists()) return candidate

        val stem = candidate.nameWithoutExtension
        val extension = candidate.extension.let { if (it.isBlank()) "" else "." + it }
        var index = 1
        while (index < 1000) {
            val next = File(folder, stem + " (" + index + ")" + extension)
            if (!next.exists()) return next
            index++
        }
        return File(folder, stem + "-" + System.currentTimeMillis() + extension)
    }

    /** A file name from a web page, made safe for a file system. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150).ifBlank { "download" }
}
