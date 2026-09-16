package com.prism.launcher.editor

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Installs the editor's web payload -- Monaco, the editor component from VS Code.
 *
 * ## Downloaded rather than bundled
 *
 * Monaco is a few megabytes of JavaScript that most Prism users will never open. Putting it in the
 * APK charges everyone for a page they may not add, and it versions independently of Prism. The
 * download happens once, into `filesDir`, and the editor works offline from then on -- which it has
 * to, because an editor that needs the network to open a file is not one.
 *
 * ## Why an npm tarball
 *
 * Monaco publishes to npm and nowhere else convenient; the tarball is the canonical artefact and
 * carries `min/vs`, the minified AMD build the host page loads. Unpacking one gzipped tar is a small
 * price for not depending on a CDN staying up.
 */
object EditorAssets {

    private const val TAG = "PrismEditor"

    /**
     * Pinned. Monaco's API changes between minors, and `prism-editor.js` is written against this
     * one -- floating to "latest" would break the editor on a day nobody changed anything.
     */
    const val MONACO_VERSION = "0.52.2"

    private val DOWNLOAD_URL =
        "https://registry.npmjs.org/monaco-editor/-/monaco-editor-$MONACO_VERSION.tgz"

    /** Written last, so its presence means the unpack finished rather than merely started. */
    private const val STAMP = ".prism-monaco"

    /** Served to the WebView at `/monaco/`. */
    fun monacoDir(context: Context): File = File(context.filesDir, "editor/monaco")

    fun isInstalled(context: Context): Boolean =
        File(monacoDir(context), STAMP).isFile && File(monacoDir(context), "vs/loader.js").isFile

    fun installedBytes(context: Context): Long =
        monacoDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Downloads and unpacks. Blocking; callers run it off the main thread.
     *
     * @return null on success, or a message describing the failure.
     */
    fun install(context: Context, onProgress: (Int, String) -> Unit): String? {
        val target = monacoDir(context)
        // A half-finished tree is not a starting point -- it looks installed to a file check and
        // then fails at load with a missing chunk.
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        var connection: HttpURLConnection? = null
        return try {
            onProgress(0, "Contacting npm…")
            connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                return "Download failed with HTTP ${connection.responseCode}"
            }

            val total = connection.contentLengthLong
            var read = 0L

            // Only `package/min/vs` is wanted: the tarball also carries the unminified sources, the
            // ESM build and TypeScript definitions, which together are several times larger than the
            // part that runs.
            val extracted = connection.inputStream.use { raw ->
                untarGz(raw, target, stripPrefix = "package/min/", onlyUnder = "vs/") { bytes ->
                    read += bytes
                    val percent = if (total > 0) ((read * 95) / total).toInt() else 0
                    onProgress(percent.coerceIn(0, 95), "Downloading editor · ${read / (1024 * 1024)} MB")
                }
            }

            if (!File(target, "vs/loader.js").isFile) {
                return "The download did not contain Monaco's loader -- the archive layout may have changed."
            }

            File(target, STAMP).writeText(MONACO_VERSION)
            onProgress(100, "Installed")
            PrismLogger.logSuccess(TAG, "Installed Monaco $MONACO_VERSION ($extracted files)")
            null
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "Editor asset install failed", t)
            runCatching { target.deleteRecursively() }
            t.message ?: t.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Streams a gzipped tar into [target].
     *
     * HAND-ROLLED because the project has no tar library and this is the only call site. The format
     * is 512-byte headers: a NUL-padded name, an octal size, a type flag, then contents padded to the
     * next 512-byte boundary.
     *
     * PATHS ARE VERIFIED, NOT TRUSTED. A tar entry can name `../../anything`, and an unpacker that
     * resolves names against a directory without checking will write outside it -- the "zip slip"
     * bug. Every destination is canonicalised and rejected unless it really lands under [target].
     */
    private fun untarGz(
        source: InputStream,
        target: File,
        stripPrefix: String,
        onlyUnder: String,
        onBytes: (Long) -> Unit,
    ): Int {
        var count = 0
        val root = target.canonicalFile
        val header = ByteArray(512)

        GZIPInputStream(CountingStream(source, onBytes).buffered(256 * 1024)).use { input ->
            while (true) {
                if (!input.readFully(header)) break
                if (header.all { it == 0.toByte() }) break        // end-of-archive

                val rawName = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                if (rawName.isEmpty()) break

                val sizeField = String(header, 124, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ').trim()
                val size = sizeField.takeIf { it.isNotEmpty() }?.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()

                val relative = if (rawName.startsWith(stripPrefix)) rawName.removePrefix(stripPrefix) else ""
                val wanted = relative.isNotEmpty() && relative.startsWith(onlyUnder)

                if (wanted && (typeFlag == '0' || typeFlag == '\u0000')) {
                    val destination = File(target, relative).canonicalFile
                    if (!destination.toPath().startsWith(root.toPath())) {
                        throw SecurityException("Archive entry escapes the install directory: $rawName")
                    }
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output -> input.copyExactly(output, size) }
                    count++
                } else if (wanted && typeFlag == '5') {
                    File(target, relative).mkdirs()
                    input.skipExactly(size)
                } else {
                    input.skipExactly(size)
                }

                val padding = (512 - (size % 512)) % 512
                if (padding > 0) input.skipExactly(padding)
            }
        }
        return count
    }

    /** Reports progress on the compressed stream, which is the only length the server declared. */
    private class CountingStream(
        private val inner: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
        override fun close() = inner.close()
    }

    private fun InputStream.readFully(into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val read = read(into, offset, into.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun InputStream.copyExactly(out: OutputStream, bytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipExactly(bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(32 * 1024)
        while (remaining > 0) {
            val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read < 0) break
            remaining -= read
        }
    }
}
