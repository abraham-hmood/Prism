package com.prism.desktop.editor

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Node.js on a desktop. PHASE 93.
 *
 * ## THIS IS NOT A PORT OF NodeInstaller, AND THAT IS THE POINT OF THE PHASE
 *
 * Android's installer downloads a 70 MB Ubuntu base rootfs, unpacks it, downloads Node into it, and
 * runs everything under PRoot -- a userspace chroot built on ptrace. None of that is overhead
 * somebody added carelessly. Android's kernel refuses to `execve` a file stored in an app's data
 * directory, which rules out the entire point of a Node extension host: language servers, formatters,
 * `tsc`, `esbuild` are all bundled executables an extension spawns. PRoot rewrites the filesystem
 * syscalls so the kernel never sees an app-data path being executed.
 *
 * A PC has no such rule. `execve` on a downloaded binary works, `child_process` works,
 * `node_modules/.bin` works. So the rootfs, the ptrace layer and the glibc-versus-musl argument are
 * all cost with no benefit here, and copying them would have been copying a workaround for a problem
 * this platform does not have.
 *
 * ## Three ways to get Node, in this order
 *
 *  1. THE ONE ON PATH. A developer's machine has Node, it is the version they chose, and `npm`
 *     beside it already has their registry configuration and their cache. Using it is both the least
 *     work and the most likely to behave the way they expect.
 *  2. ONE PRISM DOWNLOADED EARLIER, under the data directory.
 *  3. DOWNLOAD ONE. The official build from nodejs.org, for this platform and architecture, into the
 *     data directory. No repackaging by Prism, so a user can check it against the published
 *     checksums.
 *
 * A VERSION FLOOR ON THE SYSTEM ONE, because an extension host is not where somebody should discover
 * that their distribution ships Node 12. Below the floor, Prism downloads its own rather than
 * failing in the middle of loading an extension.
 */
object DesktopNode {

    private const val TAG = "PrismNode"

    /**
     * The oldest system Node that is accepted.
     *
     * 18 is the first with a stable `fetch`, `AbortController` at top level and the `node:` prefix
     * everywhere -- all three of which a modern `.vsix` assumes without declaring. Below it the
     * failures are runtime `ReferenceError`s inside an extension, which look like Prism bugs.
     */
    private const val MINIMUM_MAJOR = 18

    /** Used when nodejs.org's release index cannot be reached. An LTS, deliberately. */
    private const val FALLBACK_VERSION = "v22.11.0"

    private const val STAMP = ".prism-node-installed"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun homeDir(): File = File(PrismPlatform.host.dataDir(), "editor/node")

    /** Where a Prism-downloaded Node's executable lands. */
    private fun bundledExecutable(): File =
        if (windows) File(homeDir(), "node.exe") else File(homeDir(), "bin/node")

    /**
     * The Node this machine will use, or null if there is none yet.
     *
     * Checked in the order described in the class comment, and the answer is NOT cached: a user can
     * install Node while Prism is running, and an editor that had already decided there was none
     * would keep saying so until restarted.
     */
    fun executable(): File? {
        onPath("node")?.let { candidate ->
            val major = majorVersionOf(candidate)
            if (major != null && major >= MINIMUM_MAJOR) return candidate
            if (major != null) {
                PrismPlatform.log.info(
                    TAG,
                    "Node " + major + " is on PATH but the extension host needs " +
                        MINIMUM_MAJOR + " or newer; Prism will use its own.",
                )
            }
        }
        return bundledExecutable().takeIf { it.isFile && it.canExecute() }
    }

    /** npm beside whichever Node is in use, for installing an extension's dependencies. */
    fun npm(): File? {
        val node = executable() ?: return null
        // Beside the executable, not on PATH independently: a system Node and a bundled npm would
        // be two versions of the same toolchain disagreeing about the module layout.
        val dir = node.parentFile ?: return null
        val names = if (windows) listOf("npm.cmd", "npm.exe", "npm") else listOf("npm")
        names.forEach { name ->
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
        }
        return onPath(if (windows) "npm.cmd" else "npm")
    }

    fun isInstalled(): Boolean = executable() != null

    /** Whether the Node in use is Prism's own download rather than the system's. */
    fun isBundled(): Boolean {
        val chosen = executable() ?: return false
        return chosen.absolutePath.startsWith(homeDir().absolutePath)
    }

    fun version(): String? = executable()?.let { versionOf(it) }

    fun describe(): String {
        val chosen = executable()
            ?: return "No Node. Install one from nodejs.org, or let Prism download its own."
        return (versionOf(chosen) ?: "unknown version") + " at " + chosen.absolutePath +
            (if (isBundled()) " (downloaded by Prism)" else " (the one on your PATH)")
    }

    /**
     * Why Node cannot be had, for a page that must say so rather than offer a dead button.
     *
     * Empty when it can -- including when it would have to be downloaded, which is a wait rather
     * than an obstacle.
     */
    fun unavailableReason(): String {
        val arch = normalisedArch()
        if (arch == null) {
            return "Node publishes no official build for this architecture (" +
                System.getProperty("os.arch") + "), so Prism cannot download one. Installing Node " +
                "yourself and putting it on PATH would work."
        }
        return ""
    }

    // -- Installing -----------------------------------------------------------

    /**
     * Downloads an official Node build. Blocking; callers run it off the UI thread.
     *
     * Returns null on success, or a reason. Does nothing if a usable Node is already present, so it
     * is safe to call from a button somebody presses twice.
     */
    fun install(onProgress: (percent: Int, message: String) -> Unit = { _, _ -> }): String? {
        if (isInstalled()) return null

        val arch = normalisedArch() ?: return unavailableReason()
        val platform = if (windows) "win" else "linux"
        val version = resolveVersion()
        val extension = if (windows) "zip" else "tar.gz"
        val name = "node-" + version + "-" + platform + "-" + arch
        val url = "https://nodejs.org/dist/" + version + "/" + name + "." + extension

        onProgress(0, "Fetching " + version + " for " + platform + "-" + arch)
        val home = homeDir()
        runCatching { home.deleteRecursively() }
        home.mkdirs()

        val archive = File(home, "node." + extension)
        download(url, archive, onProgress)?.let {
            runCatching { archive.delete() }
            return it
        }

        onProgress(80, "Unpacking")
        val unpacked = runCatching {
            if (windows) unzip(archive, home, stripTopLevel = true)
            else untarGz(archive, home, stripTopLevel = true)
        }
        runCatching { archive.delete() }
        unpacked.exceptionOrNull()?.let {
            return "Could not unpack Node: " + (it.message ?: it.javaClass.simpleName)
        }

        val executable = bundledExecutable()
        if (!executable.isFile) {
            return "Node unpacked but " + executable.name + " is not where it should be."
        }
        // The tar carries the mode bits but a zip does not, and the JDK's zip reader drops them
        // either way -- so an unpacked node is not executable until this runs. On Windows it is a
        // no-op, which is correct: the extension is what makes it runnable there.
        runCatching { executable.setExecutable(true, false) }
        File(home, "bin").listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }

        // Written LAST, so its presence means the install finished rather than merely started.
        File(home, STAMP).writeText(version)
        onProgress(100, "Installed " + version)
        PrismPlatform.log.success(TAG, "Node " + version + " installed at " + home.absolutePath)
        return null
    }

    fun uninstall(): Boolean = runCatching { homeDir().deleteRecursively() }.getOrDefault(false)

    fun installedBytes(): Long = runCatching {
        homeDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    // -- Running --------------------------------------------------------------

    /**
     * Prepares a process to run under the chosen Node.
     *
     * No PRoot, no rootfs, no path rewriting -- which is the whole difference from Android. The
     * bundled Node's own `bin` is prepended to PATH so that `npm`, `npx` and anything in
     * `node_modules/.bin` resolve to the same toolchain the host is using.
     */
    fun prepare(builder: ProcessBuilder, workingDirectory: File? = null): ProcessBuilder {
        val node = executable()
        if (node != null && isBundled()) {
            val binDir = node.parentFile?.absolutePath
            if (binDir != null) {
                val environment = builder.environment()
                val key = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) }
                    ?: "PATH"
                environment[key] =
                    binDir + File.pathSeparator + (environment[key] ?: "")
            }
        }
        workingDirectory?.let { builder.directory(it) }
        return builder
    }

    /** What a terminal panel should launch. A real shell, because a desktop has one. */
    fun shellCommand(): List<String> = if (windows) {
        // powershell rather than cmd: it is what a developer on Windows is using, and `cmd` cannot
        // run most of what a project's scripts assume.
        listOf(onPath("powershell.exe")?.absolutePath ?: "powershell.exe", "-NoLogo", "-NoExit")
    } else {
        listOf(System.getenv("SHELL") ?: "/bin/sh", "-i")
    }

    // -- Internals ------------------------------------------------------------

    private fun onPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        val candidates = if (windows && !name.contains('.')) {
            listOf(name + ".exe", name + ".cmd", name)
        } else {
            listOf(name)
        }
        path.split(File.pathSeparatorChar).forEach { dir ->
            candidates.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file
            }
        }
        return null
    }

    private fun versionOf(node: File): String? = runCatching {
        // Through the shared drainer: a ceiling, because `node --version` on a broken install can
        // hang rather than fail, and the output is read WHILE it runs rather than afterwards. See
        // runWithTimeout for why that order matters even for one short line.
        com.prism.desktop.science.runWithTimeout(
            listOf(node.absolutePath, "--version"), seconds = 5,
        )?.trim()?.takeIf { it.startsWith("v") }
    }.getOrNull()

    private fun majorVersionOf(node: File): Int? =
        versionOf(node)?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()

    /**
     * Node's own name for this architecture, or null where it publishes none.
     *
     * Null is a real answer: nodejs.org has no build for 32-bit ARM Linux desktops or for anything
     * exotic, and saying so beats downloading an x64 tarball that will not run.
     */
    private fun normalisedArch(): String? =
        when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i686" -> if (windows) "x86" else null
            else -> null
        }

    /** The current LTS from nodejs.org, or the pin if the index cannot be read. */
    private fun resolveVersion(): String = runCatching {
        val connection = (URL("https://nodejs.org/dist/index.json").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Prism")
        }
        val text = connection.inputStream.use { it.readBytes().decodeToString() }
        val releases = JSONArray(text)
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            // `lts` is false for a current release and the codename string for an LTS one, so a
            // truthiness check is the documented way to read it.
            val lts = release.opt("lts")
            if (lts != null && lts != false) {
                return@runCatching release.optString("version").ifBlank { FALLBACK_VERSION }
            }
        }
        FALLBACK_VERSION
    }.getOrElse {
        PrismPlatform.log.info(TAG, "Could not read Node's release index; using " + FALLBACK_VERSION)
        FALLBACK_VERSION
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (Int, String) -> Unit,
    ): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Prism")
        }
        if (connection.responseCode !in 200..299) {
            return "nodejs.org answered " + connection.responseCode + " for " + url
        }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) {
                        // 0..75, leaving the last quarter for the unpack, so the bar does not sit
                        // at 100% through the slowest part.
                        val percent = (copied * 75 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent, (copied shr 20).toString() + " MB of " + (total shr 20) + " MB")
                        }
                    }
                }
            }
        }
        if (target.length() <= 0) return "Nothing was downloaded."
        null
    }.getOrElse { it.message ?: "The download failed" }

    /**
     * Unpacks a gzipped tar.
     *
     * Hand-rolled rather than adding commons-compress: a Node tarball is plain ustar with no
     * extensions, and the format is 512-byte headers with an octal size. [stripTopLevel] drops the
     * `node-vX-linux-x64/` prefix every official archive has, so the result is `bin/node` rather
     * than a version-named directory nothing can find afterwards.
     *
     * PATHS ARE CHECKED, because a tar entry may name `../` and this is unpacking a file from the
     * network. An entry that escapes the destination is skipped, not sanitised -- an archive that
     * contains one is not an archive to trust the rest of either, and it is logged.
     */
    private fun untarGz(archive: File, destination: File, stripTopLevel: Boolean) {
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            val root = destination.canonicalFile
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = input.read(header, read, 512 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break

                val rawName = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                if (rawName.isEmpty()) break
                val sizeField = String(header, 124, 12, Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ').trim()
                val size = sizeField.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()
                val mode = String(header, 100, 8, Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ').trim().toIntOrNull(8) ?: 0

                val name = if (stripTopLevel) rawName.substringAfter('/', "") else rawName

                if (name.isNotEmpty()) {
                    val out = File(destination, name)
                    val canonical = runCatching { out.canonicalFile }.getOrNull()
                    val safe = canonical != null &&
                        (canonical.path == root.path ||
                            canonical.path.startsWith(root.path + File.separator))
                    if (!safe) {
                        PrismPlatform.log.warn(TAG, "Skipped a tar entry that escapes: " + rawName)
                    } else when (typeFlag) {
                        '5' -> out.mkdirs()
                        '0', '\u0000' -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output ->
                                copyExactly(input, output, size)
                            }
                            // The executable bit, which is the one mode bit that matters here.
                            if (mode and 0b001_001_001 != 0) {
                                runCatching { out.setExecutable(true, false) }
                            }
                        }
                        // Symlinks and hard links: Node's tarball has a few, all inside bin/.
                        // Skipped rather than followed -- the targets they point at are also in the
                        // archive, and a dangling link is less trouble than a link that escapes.
                        else -> skipExactly(input, size)
                    }
                } else {
                    skipExactly(input, size)
                }

                // Entries are padded to a 512-byte boundary.
                val padding = ((512 - (size % 512)) % 512)
                if (padding > 0) skipExactly(input, padding)
            }
        }
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n <= 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n <= 0) break
            remaining -= n
        }
    }

    /** The Windows half. Same path check, same reason. */
    private fun unzip(archive: File, destination: File, stripTopLevel: Boolean) {
        val root = destination.canonicalFile
        java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = if (stripTopLevel) entry.name.substringAfter('/', "") else entry.name
                if (name.isEmpty()) {
                    zip.closeEntry()
                    continue
                }
                val out = File(destination, name)
                val canonical = runCatching { out.canonicalFile }.getOrNull()
                val safe = canonical != null &&
                    (canonical.path == root.path ||
                        canonical.path.startsWith(root.path + File.separator))
                if (!safe) {
                    PrismPlatform.log.warn(TAG, "Skipped a zip entry that escapes: " + entry.name)
                } else if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }
}
