package com.prism.launcher.virtualization

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Unpacks the Windows compatibility layer.
 *
 * ## What has to be here before anything can run
 *
 * Four things, and none of them are Prism's to write:
 *
 * | Component | What it does | Licence |
 * |---|---|---|
 * | Ubuntu rootfs | provides glibc, which Wine needs and Android does not have | various, Ubuntu |
 * | box64 | translates x86_64 instructions to ARM64 | MIT |
 * | Wine | implements Win32 against Linux | LGPL-2.1 |
 * | DXVK / VKD3D | translate Direct3D to Vulkan | zlib / LGPL |
 *
 * They are fetched rather than bundled for the obvious reason -- together they are well over a
 * gigabyte -- and for a less obvious one: Wine and DXVK are copyleft, so shipping them inside a
 * Prism APK carries obligations about offering corresponding source that downloading upstream
 * artefacts on the user's request does not.
 *
 * ## Where they come from
 *
 * [SOURCE_URL] has to point at an archive laid out the way [expectedLayout] describes. Prism does
 * not host one, and deliberately does not hard-code somebody else's release URL: those move, and a
 * compatibility layer that silently starts downloading from a dead or changed link is worse than one
 * that says it is not configured. The installer therefore refuses clearly until a source is set,
 * rather than guessing.
 */
object WineInstaller {

    private const val TAG = "PrismWine"

    /**
     * Where to fetch the layer from.
     *
     * Empty by default, on purpose -- see the class comment. A Winlator container archive, or one
     * built from Wine + box64 + an Ubuntu arm64 rootfs, is the right shape.
     */
    var sourceUrl: String = ""

    /** Written last, so its presence means the unpack finished rather than merely started. */
    private const val STAMP = ".prism-wine-installed"

    fun isInstalled(context: Context): Boolean =
        File(WineContainer.imageFs(context), STAMP).isFile

    fun installedBytes(context: Context): Long =
        WineContainer.imageFs(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** What the archive must contain for the launch command to find anything. */
    fun expectedLayout(): String = buildString {
        append("usr/bin/env, usr/lib/... (an arm64 Ubuntu rootfs)\n")
        append("usr/local/bin/box64\n")
        append("opt/wine/bin/wine and opt/wine/lib/wine/...\n")
        append("opt/wine/lib/wine/dxvk/*.dll (optional, for Direct3D)")
    }

    /**
     * Downloads and unpacks. Blocking; callers run it off the main thread.
     *
     * @return null on success, or a message describing the failure.
     */
    fun install(context: Context, onProgress: (Int, String) -> Unit): String? {
        if (sourceUrl.isBlank()) {
            return "No compatibility-layer source is configured. Set one in Settings > OS " +
                "Virtualization, pointing at an archive containing:\n\n" + expectedLayout()
        }

        val target = WineContainer.imageFs(context)
        // A half-unpacked rootfs looks installed to a file check and then fails deep inside Wine
        // with a missing library, which is far harder to diagnose than starting over.
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        var connection: HttpURLConnection? = null
        return try {
            onProgress(0, "Contacting the download server…")
            connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                return "Download failed with HTTP ${connection.responseCode}"
            }

            val total = connection.contentLengthLong
            var read = 0L
            val files = connection.inputStream.use { raw ->
                unzip(CountingStream(raw) { bytes ->
                    read += bytes
                    val percent = if (total > 0) ((read * 95) / total).toInt() else 0
                    onProgress(percent.coerceIn(0, 95), "Downloading · ${read / (1024 * 1024)} MB")
                }, target)
            }

            if (files == 0) return "The archive was empty"

            val wine = File(target, "opt/wine/bin/wine")
            val box64 = File(target, "usr/local/bin/box64")
            if (!wine.isFile || !box64.isFile) {
                target.deleteRecursively()
                return "That archive is not laid out as expected. It must contain:\n\n" + expectedLayout()
            }

            File(target, STAMP).writeText(System.currentTimeMillis().toString())
            onProgress(100, "Installed")
            PrismLogger.logSuccess(TAG, "Windows layer installed ($files files)")
            null
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "Windows layer install failed", t)
            runCatching { target.deleteRecursively() }
            t.message ?: t.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }

    fun uninstall(context: Context) {
        runCatching { WineContainer.imageFs(context).deleteRecursively() }
    }

    /**
     * Unpacks a zip into [target], refusing any entry that would escape it.
     *
     * The symlink question matters more here than in the other unpackers Prism has: a rootfs is full
     * of symlinks (`/usr/lib/libfoo.so -> libfoo.so.1`), and zip carries them as entries with a
     * target path. They are recreated only when they stay inside the rootfs, because a symlink
     * pointing at `/data/data/...` would let a guest program reach straight out of the container.
     */
    private fun unzip(source: InputStream, target: File): Int {
        var count = 0
        val root = target.canonicalFile

        ZipInputStream(source.buffered(512 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(target, entry.name).canonicalFile

                if (!destination.toPath().startsWith(root.toPath())) {
                    throw SecurityException("Archive entry escapes the rootfs: ${entry.name}")
                }

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out, 256 * 1024) }
                    count++
                }
                zip.closeEntry()
            }
        }
        return count
    }

    private class CountingStream(
        private val inner: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
        override fun close() = inner.close()
    }
}
